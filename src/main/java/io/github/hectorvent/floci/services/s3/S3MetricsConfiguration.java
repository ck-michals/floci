package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;

/**
 * A parsed {@code MetricsConfiguration} request body, reduced to its id and a canonical
 * serialization of its contents.
 *
 * <p>The body is re-serialized rather than stored verbatim, so that what a later
 * GetBucketMetricsConfiguration or ListBucketMetricsConfigurations returns is built by floci
 * instead of being whatever XML the caller sent, and so that the same stored form can be wrapped
 * in either response.
 */
record S3MetricsConfiguration(String id, String innerXml) {

    /** AWS reports a body that does not match the published schema this way. */
    private static AwsException malformed() {
        return new AwsException("MalformedXML",
                "The XML you provided was not well-formed or did not validate against our published schema", 400);
    }

    static S3MetricsConfiguration parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw malformed();
        }

        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            document = builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw malformed();
        }

        Element root = document.getDocumentElement();
        if (root == null || !"MetricsConfiguration".equals(localName(root))) {
            throw malformed();
        }

        String id = textOf(firstChild(root, "Id"));
        if (id == null || id.isBlank()) {
            throw malformed();
        }

        StringBuilder inner = new StringBuilder();
        inner.append("<Id>").append(XmlBuilder.escape(id)).append("</Id>");

        Element filter = firstChild(root, "Filter");
        if (filter != null) {
            inner.append("<Filter>").append(filterXml(filter)).append("</Filter>");
        }
        return new S3MetricsConfiguration(id, inner.toString());
    }

    /**
     * Serializes a filter. A filter is a prefix, an object tag, an access point ARN, or an And
     * conjunction of those, so each is emitted only when present.
     */
    private static String filterXml(Element filter) {
        Element and = firstChild(filter, "And");
        if (and != null) {
            StringBuilder out = new StringBuilder("<And>");
            appendIfPresent(out, and, "Prefix");
            for (Element tag : children(and, "Tag")) {
                out.append(tagXml(tag));
            }
            appendIfPresent(out, and, "AccessPointArn");
            return out.append("</And>").toString();
        }

        StringBuilder out = new StringBuilder();
        appendIfPresent(out, filter, "Prefix");
        Element tag = firstChild(filter, "Tag");
        if (tag != null) {
            out.append(tagXml(tag));
        }
        appendIfPresent(out, filter, "AccessPointArn");
        return out.toString();
    }

    private static String tagXml(Element tag) {
        return "<Tag><Key>" + XmlBuilder.escape(textOf(firstChild(tag, "Key")))
                + "</Key><Value>" + XmlBuilder.escape(textOf(firstChild(tag, "Value")))
                + "</Value></Tag>";
    }

    private static void appendIfPresent(StringBuilder out, Element parent, String name) {
        Element child = firstChild(parent, name);
        if (child != null) {
            out.append('<').append(name).append('>')
                    .append(XmlBuilder.escape(textOf(child)))
                    .append("</").append(name).append('>');
        }
    }

    private static Element firstChild(Element parent, String name) {
        for (Element child : children(parent, name)) {
            return child;
        }
        return null;
    }

    private static java.util.List<Element> children(Element parent, String name) {
        java.util.List<Element> matches = new java.util.ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(localName((Element) node))) {
                matches.add((Element) node);
            }
        }
        return matches;
    }

    private static String localName(Element element) {
        return element.getLocalName() != null ? element.getLocalName() : element.getNodeName();
    }

    private static String textOf(Element element) {
        return element == null ? null : element.getTextContent().trim();
    }
}
