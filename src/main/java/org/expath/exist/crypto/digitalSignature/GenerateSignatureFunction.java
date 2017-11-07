/*
 *  eXist Java Cryptographic Extension
 *  Copyright (C) 2010 Claudius Teodorescu at http://kuberam.ro
 *  
 *  Released under LGPL License - http://gnu.org/licenses/lgpl.html.
 *  
 */
package org.expath.exist.crypto.digitalSignature;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URISyntaxException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.exist.Namespaces;
import org.exist.dom.memtree.SAXAdapter;
import org.exist.dom.persistent.BinaryDocument;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.lock.Lock;
import org.exist.storage.serializers.Serializer;
import org.exist.validation.internal.node.NodeInputStream;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.BasicFunction;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.value.FunctionParameterSequenceType;
import org.exist.xquery.value.NodeValue;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.Type;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import ro.kuberam.libs.java.crypto.ErrorMessages;
import ro.kuberam.libs.java.crypto.ExpathCryptoModule;
import ro.kuberam.libs.java.crypto.digitalSignature.GenerateXmlSignature;

import static org.exist.xquery.FunctionDSL.*;
import static org.expath.exist.crypto.ExistExpathCryptoModule.*;

/**
 * @author Claudius Teodorescu (claudius.teodorescu@gmail.com)
 */
public class GenerateSignatureFunction extends BasicFunction {

    private static final Logger LOG = LogManager.getLogger(GenerateSignatureFunction.class);

    private static final String FS_GENERATE_SIGNATURE_NAME = "generate-signature";
    private static final FunctionParameterSequenceType FS_GENERATE_SIGNATURE_PARAM_DATA = param("data", Type.NODE, "The document to be signed.");
    private static final FunctionParameterSequenceType FS_GENERATE_SIGNATURE_PARAM_CANONICALIZATION_ALGORITHM = param("canonicalization-algorithm", Type.STRING, "Canonicalization Algorithm.");
    private static final FunctionParameterSequenceType FS_GENERATE_SIGNATURE_PARAM_DIGEST_ALGORITHM = param("digest-algorithm", Type.STRING, ExpathCryptoModule.DIGEST_ALGORITHM);
    private static final FunctionParameterSequenceType FS_GENERATE_SIGNATURE_PARAM_SIGNATURE_ALGORITHM = param("signature-algorithm", Type.STRING, ExpathCryptoModule.SIGNATURE_ALGORITHM);
    private static final FunctionParameterSequenceType FS_GENERATE_SIGNATURE_PARAM_SIGNATURE_NAMESPACE_PREFIX = param("signature-namespace-prefix", Type.STRING, "The namespace prefix for signature.");
    private static final FunctionParameterSequenceType FS_GENERATE_SIGNATURE_PARAM_SIGNATURE_TYPE = param("signature-type", Type.STRING, ExpathCryptoModule.SIGNATURE_TYPE);
    private static final FunctionParameterSequenceType FS_GENERATE_SIGNATURE_PARAM_DIGITAL_CERTIFICATE = param("digital-certificate", Type.ANY_TYPE, ExpathCryptoModule.digitalCertificateDetailsDescription);
    private static final FunctionParameterSequenceType FS_GENERATE_SIGNATURE_PARAM_XPATH = param("xpath-expression", Type.ANY_TYPE, "The XPath expression used for selecting the subset to be signed.");

    public static final FunctionSignature FS_GENERATE_SIGNATURE[] = functionSignatures(
        FS_GENERATE_SIGNATURE_NAME,
        "Generate an XML digital signature based on generated key pair. This signature is for the whole document.",
        returns(Type.NODE, "the signed document (or signature) as node()."),
        arities(
            arity(
                FS_GENERATE_SIGNATURE_PARAM_DATA,
                FS_GENERATE_SIGNATURE_PARAM_CANONICALIZATION_ALGORITHM,
                FS_GENERATE_SIGNATURE_PARAM_DIGEST_ALGORITHM,
                FS_GENERATE_SIGNATURE_PARAM_SIGNATURE_ALGORITHM,
                FS_GENERATE_SIGNATURE_PARAM_SIGNATURE_NAMESPACE_PREFIX,
                FS_GENERATE_SIGNATURE_PARAM_SIGNATURE_TYPE
            ),
            arity(
                FS_GENERATE_SIGNATURE_PARAM_DATA,
                FS_GENERATE_SIGNATURE_PARAM_CANONICALIZATION_ALGORITHM,
                FS_GENERATE_SIGNATURE_PARAM_DIGEST_ALGORITHM,
                FS_GENERATE_SIGNATURE_PARAM_SIGNATURE_ALGORITHM,
                FS_GENERATE_SIGNATURE_PARAM_SIGNATURE_NAMESPACE_PREFIX,
                FS_GENERATE_SIGNATURE_PARAM_SIGNATURE_TYPE,
                FS_GENERATE_SIGNATURE_PARAM_XPATH
            ),
            arity(
                FS_GENERATE_SIGNATURE_PARAM_DATA,
                FS_GENERATE_SIGNATURE_PARAM_CANONICALIZATION_ALGORITHM,
                FS_GENERATE_SIGNATURE_PARAM_DIGEST_ALGORITHM,
                FS_GENERATE_SIGNATURE_PARAM_SIGNATURE_ALGORITHM,
                FS_GENERATE_SIGNATURE_PARAM_SIGNATURE_NAMESPACE_PREFIX,
                FS_GENERATE_SIGNATURE_PARAM_SIGNATURE_TYPE,
                FS_GENERATE_SIGNATURE_PARAM_DIGITAL_CERTIFICATE
            ),
            arity(
                FS_GENERATE_SIGNATURE_PARAM_DATA,
                FS_GENERATE_SIGNATURE_PARAM_CANONICALIZATION_ALGORITHM,
                FS_GENERATE_SIGNATURE_PARAM_DIGEST_ALGORITHM,
                FS_GENERATE_SIGNATURE_PARAM_SIGNATURE_ALGORITHM,
                FS_GENERATE_SIGNATURE_PARAM_SIGNATURE_NAMESPACE_PREFIX,
                FS_GENERATE_SIGNATURE_PARAM_SIGNATURE_TYPE,
                FS_GENERATE_SIGNATURE_PARAM_XPATH,
                FS_GENERATE_SIGNATURE_PARAM_DIGITAL_CERTIFICATE
            )
        )
    );

    private static final String certificateRootElementName = "digital-certificate";
    private static final String[] certificateChildElementNames = {"keystore-type", "keystore-password",
            "key-alias", "private-key-password", "keystore-uri"};

    public GenerateSignatureFunction(final XQueryContext context, final FunctionSignature signature) {
        super(context, signature);
    }

    @Override
    public Sequence eval(final Sequence[] args, final Sequence contextSequence) throws XPathException {
        final Serializer serializer = context.getBroker().getSerializer();
        final NodeValue inputNode = (NodeValue) args[0].itemAt(0);
        final InputStream inputNodeStream = new NodeInputStream(serializer, inputNode);
        final Document inputDOMDoc = inputStreamToDocument(inputNodeStream);

        final String canonicalizationAlgorithm = args[1].getStringValue();
        final String digestAlgorithm = args[2].getStringValue();
        final String signatureAlgorithm = args[3].getStringValue();
        final String signatureNamespacePrefix = args[4].getStringValue();
        final String signatureType = args[5].getStringValue();

        String signatureString = null;
        Document signatureDocument = null;

        // get the XPath expression and/or the certificate's details
        String xpathExprString = null;
        String[] certificateDetails = new String[5];
        certificateDetails[0] = "";
        InputStream keyStoreInputStream = null;

        // function with 7 arguments
        if (args.length == 7) {
            if (args[6].itemAt(0).getType() == 22) {
                xpathExprString = args[6].getStringValue();
            } else if (args[6].itemAt(0).getType() == 1) {
                final Node certificateDetailsNode = ((NodeValue) args[6].itemAt(0)).getNode();
                // get the certificate details
                certificateDetails = getDigitalCertificateDetails(certificateDetails,
                        certificateDetailsNode);
                // get the keystore InputStream
                keyStoreInputStream = getKeyStoreInputStream(certificateDetails[4]);
            }
        }

        // function with 8 arguments
        if (args.length == 8) {
            xpathExprString = args[6].getStringValue();
            final Node certificateDetailsNode = ((NodeValue) args[7].itemAt(0)).getNode();
            // get the certificate details
            certificateDetails = getDigitalCertificateDetails(certificateDetails, certificateDetailsNode);
            // get the keystore InputStream
            keyStoreInputStream = getKeyStoreInputStream(certificateDetails[4]);
        }

        try {
            signatureString = GenerateXmlSignature.generate(inputDOMDoc, canonicalizationAlgorithm,
                    digestAlgorithm, signatureAlgorithm, signatureNamespacePrefix, signatureType,
                    xpathExprString, certificateDetails, keyStoreInputStream);
        } catch (final Exception ex) {
            throw new XPathException(ex.getMessage());
        }

        try {
            signatureDocument = stringToDocument(signatureString);
        } catch (final Exception ex) {
            throw new XPathException(ex.getMessage());
        }

        return (Sequence) signatureDocument;
    }

    private Document stringToDocument(final String signatureString) throws Exception {
        // process the output (signed) document from string to node()
        try {
            final SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            final SAXParser parser = factory.newSAXParser();
            final XMLReader xr = parser.getXMLReader();
            final SAXAdapter adapter = new SAXAdapter(context);
            xr.setContentHandler(adapter);
            xr.setProperty(Namespaces.SAX_LEXICAL_HANDLER, adapter);
            xr.parse(new InputSource(new StringReader(signatureString)));

            return adapter.getDocument();

        } catch (final ParserConfigurationException e) {
            throw new XPathException("Error while constructing XML parser: " + e.getMessage());
        } catch (final SAXException | IOException e) {
            throw new XPathException("Error while parsing XML: " + e.getMessage());
        }
    }

    private String[] getDigitalCertificateDetails(final String[] certificateDetails, final Node certificateDetailsNode)
            throws XPathException {
        if (!certificateDetailsNode.getNodeName().equals(certificateRootElementName)) {
            throw new XPathException(ErrorMessages.error_sigElem);
            // TODO: here was err:CX05 The root element of argument
            // $digital-certificate must have the name 'digital-certificate'.
        }

        final NodeList certificateDetailsNodeList = certificateDetailsNode.getChildNodes();
        for (int i = 0, il = certificateDetailsNodeList.getLength(); i < il; i++) {
            final Node child = certificateDetailsNodeList.item(i);
            if (child.getNodeName().equals(certificateChildElementNames[i])) {
                certificateDetails[i] = child.getFirstChild().getNodeValue();
            } else {
                throw new XPathException(ErrorMessages.error_sigElem);
                // TODO: here was err:CX05 The root element of argument
                // $digital-certificate must have the name
                // 'digital-certificate'.
            }
        }
        return certificateDetails;
    }

    private InputStream getKeyStoreInputStream(final String keystoreURI) throws XPathException {
        // get the keystore as InputStream
        try {
            try {
                final DocumentImpl keyStoreDoc = context.getBroker().getXMLResource(XmldbURI.xmldbUriFor(keystoreURI), Lock.LockMode.READ_LOCK);
                if (keyStoreDoc == null) {
                    throw new XPathException(ErrorMessages.error_readKeystore);
                    // TODO: here was err:CX07 The keystore is null.
                }

                final BinaryDocument keyStoreBinaryDoc = (BinaryDocument) keyStoreDoc;
                try {
                    return context.getBroker().getBinaryResource(keyStoreBinaryDoc);
                } catch (final IOException ex) {
                    throw new XPathException(ErrorMessages.error_readKeystore);
                }

            } catch (final PermissionDeniedException ex) {
                LOG.error(ErrorMessages.error_deniedKeystore);
                return null;
            }
        } catch (final URISyntaxException ex) {
            LOG.error(ErrorMessages.error_keystoreUrl);
            return null;
        }
    }

    private Document inputStreamToDocument(final InputStream inputStream) {
        // initialize the document builder
        final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = null;
        try {
            db = dbf.newDocumentBuilder();
        } catch (ParserConfigurationException ex) {
        }

        // convert data to DOM document
        Document document = null;
        try {
            document = db.parse(inputStream);
        } catch (SAXException | IOException ex) {
            ex.getMessage();
        }

        return document;
    }
}