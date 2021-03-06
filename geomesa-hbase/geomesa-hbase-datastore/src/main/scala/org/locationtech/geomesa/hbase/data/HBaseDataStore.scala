/***********************************************************************
 * Copyright (c) 2013-2019 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.hbase.data

import java.util.Collections

import org.apache.hadoop.hbase.TableName
import org.apache.hadoop.hbase.client._
import org.apache.hadoop.hbase.filter.FilterList
import org.apache.hadoop.hbase.security.visibility.Authorizations
import org.apache.hadoop.hbase.zookeeper.ZKConfig
import org.geotools.data.Query
import org.locationtech.geomesa.hbase.coprocessor.GeoMesaCoprocessor
import org.locationtech.geomesa.hbase.coprocessor.aggregators.HBaseVersionAggregator
import org.locationtech.geomesa.hbase.data.HBaseDataStoreFactory.HBaseDataStoreConfig
import org.locationtech.geomesa.index.geotools.GeoMesaDataStore
import org.locationtech.geomesa.index.index.attribute.AttributeIndex
import org.locationtech.geomesa.index.index.id.IdIndex
import org.locationtech.geomesa.index.index.z2.{XZ2Index, Z2Index}
import org.locationtech.geomesa.index.index.z3.{XZ3Index, Z3Index}
import org.locationtech.geomesa.index.metadata.{GeoMesaMetadata, MetadataStringSerializer}
import org.locationtech.geomesa.index.stats.{DistributedRunnableStats, GeoMesaStats, UnoptimizedRunnableStats}
import org.locationtech.geomesa.index.utils._
import org.locationtech.geomesa.utils.conf.IndexId
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes.AttributeOptions
import org.locationtech.geomesa.utils.io.WithClose
import org.locationtech.geomesa.utils.stats.IndexCoverage
import org.locationtech.geomesa.utils.zk.ZookeeperLocking
import org.opengis.feature.simple.SimpleFeatureType

import scala.util.control.NonFatal

class HBaseDataStore(val connection: Connection, override val config: HBaseDataStoreConfig)
    extends GeoMesaDataStore[HBaseDataStore](config) with ZookeeperLocking {

  import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType

  import scala.collection.JavaConverters._

  override val metadata: GeoMesaMetadata[String] =
    new HBaseBackedMetadata(connection, TableName.valueOf(config.catalog), MetadataStringSerializer)

  override val adapter: HBaseIndexAdapter = new HBaseIndexAdapter(this)

  override val stats: GeoMesaStats =
    if (config.remoteFilter) { new DistributedRunnableStats(this) } else { new UnoptimizedRunnableStats(this) }

  // zookeeper locking
  override protected val mock: Boolean = false
  override protected val zookeepers: String = ZKConfig.getZKQuorumServersString(connection.getConfiguration)

  override def delete(): Unit = {
    val tables = getTypeNames.flatMap(getAllIndexTableNames)
    WithClose(connection.getAdmin) { admin =>
      (tables.distinct :+ config.catalog).map(TableName.valueOf).par.foreach { table =>
        admin.disableTable(table)
        admin.deleteTable(table)
      }
    }
  }

  override def getQueryPlan(query: Query, index: Option[String], explainer: Explainer): Seq[HBaseQueryPlan] =
    super.getQueryPlan(query, index, explainer).asInstanceOf[Seq[HBaseQueryPlan]]

  def applySecurity(query: org.apache.hadoop.hbase.client.Query): Unit =
    authOpt.foreach(query.setAuthorizations)

  def applySecurity(queries: Iterable[org.apache.hadoop.hbase.client.Query]): Unit =
    authOpt.foreach(a => queries.foreach(_.setAuthorizations(a)))

  override protected def loadIteratorVersions: Set[String] = {
    import org.locationtech.geomesa.utils.conversions.ScalaImplicits.RichIterator

    // just check the first table available
    val versions = getTypeNames.iterator.map(getSchema).flatMap { sft =>
      manager.indices(sft).iterator.flatMap { index =>
        index.getTableNames(None).flatMap { table =>
          try {
            val name = TableName.valueOf(table)
            if (connection.getAdmin.tableExists(name)) {
              val options = HBaseVersionAggregator.configure(sft, index)
              WithClose(connection.getTable(name)) { t =>
                WithClose(GeoMesaCoprocessor.execute(t, new Scan().setFilter(new FilterList()), options)) { bytes =>
                  bytes.map(_.toStringUtf8).toList.iterator // force evaluation of the iterator before closing it
                }
              }
            } else {
              Iterator.empty
            }
          } catch {
            case NonFatal(_) => Iterator.empty
          }
        }
      }
    }
    versions.headOption.toSet
  }

  override protected def transitionIndices(sft: SimpleFeatureType): Unit = {
    val dtg = sft.getDtgField.toSeq
    val geom = Option(sft.getGeomField).toSeq

    val indices = Seq.newBuilder[IndexId]
    val tableNameKeys = Seq.newBuilder[(String, String)]

    sft.getIndices.foreach {
      case id if id.name == IdIndex.name =>
        require(id.version == 1, s"Expected index version of 1 but got: $id")
        indices += id.copy(version = 3)
        tableNameKeys += ((s"table.${IdIndex.name}.v1", s"table.${IdIndex.name}.v3"))

      case id if id.name == Z3Index.name =>
        require(id.version <= 2, s"Expected index version of 1 or 2 but got: $id")
        indices += id.copy(attributes = geom ++ dtg, version = id.version + 3)
        tableNameKeys += ((s"table.${Z3Index.name}.v${id.version}", s"table.${Z3Index.name}.v${id.version + 3}"))

      case id if id.name == XZ3Index.name =>
        require(id.version == 1, s"Expected index version of 1 but got: $id")
        indices += id.copy(attributes = geom ++ dtg)

      case id if id.name == Z2Index.name =>
        require(id.version <= 2, s"Expected index version of 1 or 2 but got: $id")
        indices += id.copy(attributes = geom, version = id.version + 2)
        tableNameKeys += ((s"table.${Z2Index.name}.v${id.version}", s"table.${Z2Index.name}.v${id.version + 2}"))

      case id if id.name == XZ2Index.name =>
        require(id.version == 1, s"Expected index version of 1 but got: $id")
        indices += id.copy(attributes = geom)

      case id if id.name == AttributeIndex.name =>
        require(id.version <= 5, s"Expected index version of 1-5 but got: $id")
        lazy val fields = if (id.version == 1) { dtg } else { geom ++ dtg }
        sft.getAttributeDescriptors.asScala.foreach { d =>
          val index = d.getUserData.remove(AttributeOptions.OPT_INDEX).asInstanceOf[String]
          if (index == null || index.equalsIgnoreCase("false") || index.equalsIgnoreCase(IndexCoverage.NONE.toString)) {
            // no-op
          } else if (java.lang.Boolean.valueOf(index) || index.equalsIgnoreCase(IndexCoverage.FULL.toString) ||
              index.equalsIgnoreCase(IndexCoverage.JOIN.toString)) {
            indices += id.copy(attributes = Seq(d.getLocalName) ++ fields, version = id.version + 2)
          } else {
            throw new IllegalStateException(s"Expected an index coverage or boolean but got: $index")
          }
        }
        tableNameKeys ++=
            Seq(s"table.${AttributeIndex.name}.v${id.version}", "tables.idx.attr.name")
                .map((_, s"table.${AttributeIndex.name}.v${id.version + 2}"))
    }

    sft.setIndices(indices.result)

    // update metadata keys for tables
    tableNameKeys.result.foreach { case (from, to) =>
      metadata.scan(sft.getTypeName, from, cache = false).foreach { case (key, name) =>
        metadata.insert(sft.getTypeName, to + key.substring(from.length), name)
        metadata.remove(sft.getTypeName, key)
      }
    }
  }

  private def authOpt: Option[Authorizations] =
    config.authProvider.map { provider =>
      val auths = provider.getAuthorizations
      // HBase seems to treat and empty collection as no auths
      // which forces it to default to the user's full set of auths
      new Authorizations(if (auths.isEmpty) { HBaseDataStore.EmptyAuths } else { auths })
    }
}

object HBaseDataStore {
  val EmptyAuths: java.util.List[String] = Collections.singletonList("")
}
