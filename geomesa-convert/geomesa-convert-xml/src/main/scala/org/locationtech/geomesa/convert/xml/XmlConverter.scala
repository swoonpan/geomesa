/***********************************************************************
 * Copyright (c) 2013-2019 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.convert.xml

import java.io._
import java.nio.charset.Charset

import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI
import javax.xml.namespace.NamespaceContext
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import javax.xml.xpath.{XPath, XPathConstants, XPathExpression, XPathFactory}
import org.apache.commons.io.IOUtils
import org.apache.commons.io.input.BOMInputStream
import org.locationtech.geomesa.convert.Modes.{ErrorMode, LineMode, ParseMode}
import org.locationtech.geomesa.convert._
import org.locationtech.geomesa.convert.xml.XmlConverter.{XmlConfig, XmlField, XmlOptions}
import org.locationtech.geomesa.convert2.transforms.Expression
import org.locationtech.geomesa.convert2.{AbstractConverter, ConverterConfig, ConverterOptions, Field}
import org.locationtech.geomesa.utils.collection.CloseableIterator
import org.locationtech.geomesa.utils.io.WithClose
import org.locationtech.geomesa.utils.text.TextTools
import org.opengis.feature.simple.SimpleFeatureType
import org.w3c.dom.{Element, NodeList}
import org.xml.sax.InputSource

import scala.util.control.NonFatal

class XmlConverter(sft: SimpleFeatureType, config: XmlConfig, fields: Seq[XmlField], options: XmlOptions)
    extends AbstractConverter[Element, XmlConfig, XmlField, XmlOptions](sft, config, fields, options) {

  import scala.collection.JavaConverters._

  private val docBuilder = {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setNamespaceAware(true)
    factory.newDocumentBuilder()
  }

  private val xmlValidator = config.xsd.map { path =>
    val schemaFactory = SchemaFactory.newInstance(W3C_XML_SCHEMA_NS_URI)
    WithClose(getClass.getClassLoader.getResourceAsStream(path)) { xsdStream =>
      schemaFactory.newSchema(new StreamSource(xsdStream)).newValidator()
    }
  }

  private val xpath = {
    val factory = try {
      val res = XPathFactory.newInstance(XPathFactory.DEFAULT_OBJECT_MODEL_URI, config.xpathFactory, getClass.getClassLoader)
      logger.info(s"Loaded xpath factory ${res.getClass}")
      res
    } catch {
      case NonFatal(e) =>
        logger.warn(s"Unable to load xpath provider '${config.xpathFactory}': ${e.toString}. " +
            "Xpath queries may be slower - check your classpath")
        XPathFactory.newInstance(XPathFactory.DEFAULT_OBJECT_MODEL_URI)
    }
    val xp = factory.newXPath()
    if (config.xmlNamespaces.nonEmpty) {
      xp.setNamespaceContext(new NamespaceContext() {
        override def getPrefix(namespaceURI: String): String = null
        override def getPrefixes(namespaceURI: String): java.util.Iterator[_] = null
        override def getNamespaceURI(prefix: String): String = config.xmlNamespaces.getOrElse(prefix, null)
      })
    }
    xp
  }

  private val rootPath = config.featurePath.map(xpath.compile)

  fields.foreach(_.compile(xpath))

  // TODO GEOMESA-1039 more efficient InputStream processing for multi mode

  override protected def parse(is: InputStream, ec: EvaluationContext): CloseableIterator[Element] = {
    // detect and exclude the BOM if it exists
    val bis = new BOMInputStream(is)
    if (options.lineMode == LineMode.Single) {
      val lines = IOUtils.lineIterator(bis, options.encoding)
      val elements = lines.asScala.flatMap { line =>
        ec.counter.incLineCount()
        if (TextTools.isWhitespace(line)) { Iterator.empty } else {
          Iterator.single(parseDocument(new StringReader(line)))
        }
      }
      CloseableIterator(elements, lines.close())
    } else {
      val reader = new InputStreamReader(bis, options.encoding)
      CloseableIterator.fill(1, reader.close()) { ec.counter.incLineCount(); parseDocument(reader) }
    }
  }

  override protected def values(parsed: CloseableIterator[Element],
                                ec: EvaluationContext): CloseableIterator[Array[Any]] = {
    val array = Array.ofDim[Any](2)
    rootPath match {
      case None =>
        parsed.map { element =>
          array(0) = element
          array
        }

      case Some(path) =>
        parsed.flatMap { element =>
          array(1) = element
          val nodeList = path.evaluate(element, XPathConstants.NODESET).asInstanceOf[NodeList]
          Iterator.tabulate(nodeList.getLength) { i =>
            array(0) = nodeList.item(i)
            array
          }
        }
    }
  }

  private  def parseDocument(reader: Reader): Element = {
    // parse the document once, then extract each feature node and operate on it
    val document = docBuilder.parse(new InputSource(reader))
    // if a schema is defined, validate it - this will throw an exception on failure
    xmlValidator.foreach(_.validate(new DOMSource(document)))
    document.getDocumentElement
  }
}

object XmlConverter extends StrictLogging {

  // paths can be absolute, or relative to the feature node
  // they can also include xpath functions to manipulate the result
  // feature path can be any xpath that resolves to a node set (or a single node)

  case class XmlConfig(`type`: String,
                       xpathFactory: String,
                       xmlNamespaces: Map[String, String],
                       xsd: Option[String],
                       featurePath: Option[String],
                       idField: Option[Expression],
                       caches: Map[String, Config],
                       userData: Map[String, Expression]) extends ConverterConfig

  sealed trait XmlField extends Field {
    def compile(xpath: XPath): Unit
  }

  case class DerivedField(name: String, transforms: Option[Expression]) extends XmlField {
    override def compile(xpath: XPath): Unit = {}
  }

  case class XmlPathField(name: String, path: String, transforms: Option[Expression]) extends XmlField {

    private var expression: XPathExpression = _

    private val mutableArray = Array.ofDim[Any](1)

    override def compile(xpath: XPath): Unit = expression = xpath.compile(path)

    override def eval(args: Array[Any])(implicit ec: EvaluationContext): Any = {
      mutableArray(0) = expression.evaluate(args(0))
      super.eval(mutableArray)
    }
  }

  case class XmlOptions(validators: SimpleFeatureValidator,
                        parseMode: ParseMode,
                        errorMode: ErrorMode,
                        lineMode: LineMode,
                        encoding: Charset,
                        verbose: Boolean) extends ConverterOptions
}
