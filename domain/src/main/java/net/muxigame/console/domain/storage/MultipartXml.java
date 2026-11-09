package net.muxigame.console.domain.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Only bounded provider control XML is accepted. No DTD, schema, XInclude or external entities. */
final class MultipartXml {
    private MultipartXml() { }
    static Element parse(byte[] bytes, String rootName) throws IOException {
        if (bytes.length < 1 || bytes.length > CloudObjectStorage.MAX_CONTROL_BYTES) throw new IOException("Invalid storage control XML size");
        try {
            var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder(); builder.setErrorHandler(new DefaultHandler() {
                @Override public void error(SAXParseException error) throws SAXParseException { throw error; }
                @Override public void fatalError(SAXParseException error) throws SAXParseException { throw error; }
            });
            Element root = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
            if (!rootName.equals(root.getLocalName())) throw new IOException("Storage control operation did not succeed");
            return root;
        } catch (Exception invalid) { throw new IOException("Invalid storage control response"); }
    }
    static List<Element> children(Element root, String name) throws IOException {
        var result = new ArrayList<Element>(); var nodes = root.getChildNodes();
        if (nodes.getLength() > 50_000) throw new IOException("Oversize storage XML structure");
        for (int i = 0; i < nodes.getLength(); i++) if (nodes.item(i) instanceof Element child && name.equals(child.getLocalName())) result.add(child);
        return result;
    }
    static String text(Element root, String name) throws IOException {
        var values = children(root, name);
        if (values.size() != 1 || values.getFirst().getTextContent().length() > 4096) throw new IOException("Missing or duplicate storage XML field");
        Element value = values.getFirst();
        for (int i=0;i<value.getChildNodes().getLength();i++) if(value.getChildNodes().item(i) instanceof Element) throw new IOException("Invalid nested storage XML field");
        return value.getTextContent();
    }
    static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }
}
