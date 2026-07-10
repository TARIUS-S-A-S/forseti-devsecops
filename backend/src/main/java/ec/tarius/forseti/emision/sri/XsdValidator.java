package ec.tarius.forseti.emision.sri;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

/**
 * Valida XML contra los XSD oficiales SRI.
 *
 * Los schemas se cargan UNA SOLA VEZ al startup (los Schema objects son thread-safe
 * según JAXP). Los Validators NO son thread-safe, así que se crean por validación.
 *
 * Para Sprint 3 fase A solo cargamos factura v2.1.0; cuando entren NC/ND/retención
 * en sprints siguientes se agrega cada XSD aquí (lazy load por tipo).
 */
@Component
public class XsdValidator {

    private static final Logger log = LoggerFactory.getLogger(XsdValidator.class);

    private final Schema facturaSchema;
    private final Schema notaCreditoSchema;

    public XsdValidator() {
        this.facturaSchema = cargar("sri/xsd/factura_v2.1.0.xsd");
        this.notaCreditoSchema = cargar("sri/xsd/notaCredito_v1.1.0.xsd");
        log.info("XSDs cargados: factura v2.1.0 + notaCredito v1.1.0");
    }

    /** Valida un Document de factura. Lanza {@link XsdValidationException} si no cumple. */
    public void validarFactura(Document doc) {
        try {
            Validator v = facturaSchema.newValidator();
            v.validate(new DOMSource(doc));
        } catch (SAXException e) {
            throw new XsdValidationException("XML no cumple XSD factura v2.1.0: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new XsdValidationException("Error de I/O validando XML factura", e);
        }
    }

    /** Valida un blob de bytes (lo que se enviaría al SRI). */
    public void validarFactura(byte[] xml) {
        try {
            Validator v = facturaSchema.newValidator();
            v.validate(new StreamSource(new ByteArrayInputStream(xml)));
        } catch (SAXException e) {
            throw new XsdValidationException("XML no cumple XSD factura v2.1.0: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new XsdValidationException("Error de I/O validando XML factura", e);
        }
    }

    /** Valida un Document de Nota de Crédito (codDoc=04, root {@code <notaCredito>}). */
    public void validarNotaCredito(Document doc) {
        try {
            Validator v = notaCreditoSchema.newValidator();
            v.validate(new DOMSource(doc));
        } catch (SAXException e) {
            throw new XsdValidationException("XML no cumple XSD notaCredito v1.1.0: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new XsdValidationException("Error de I/O validando XML notaCredito", e);
        }
    }

    /** Variante para validar el blob ya serializado de la NC. */
    public void validarNotaCredito(byte[] xml) {
        try {
            Validator v = notaCreditoSchema.newValidator();
            v.validate(new StreamSource(new ByteArrayInputStream(xml)));
        } catch (SAXException e) {
            throw new XsdValidationException("XML no cumple XSD notaCredito v1.1.0: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new XsdValidationException("Error de I/O validando XML notaCredito", e);
        }
    }

    private static Schema cargar(String classpath) {
        try {
            SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            sf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            sf.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            sf.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            // Resolver para imports relativos del XSD (e.g. xmldsig-core-schema.xsd que importa
            // el XSD oficial del SRI). Solo permite resolver desde classpath sibling — bloquea
            // cualquier URL http/file remoto (XXE-safe).
            sf.setResourceResolver(classpathSiblingResolver(classpath));
            try (var in = new ClassPathResource(classpath).getInputStream()) {
                return sf.newSchema(new StreamSource(in));
            }
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo cargar el XSD " + classpath, e);
        }
    }

    private static LSResourceResolver classpathSiblingResolver(String basePath) {
        String dir = basePath.contains("/") ? basePath.substring(0, basePath.lastIndexOf('/') + 1) : "";
        return (type, namespaceURI, publicId, systemId, baseURI) -> {
            if (systemId == null) return null;
            String sibling = dir + systemId;
            try {
                InputStream stream = new ClassPathResource(sibling).getInputStream();
                return new ClasspathLSInput(publicId, systemId, baseURI, stream);
            } catch (IOException e) {
                log.warn("No se pudo resolver schema importado: {} (intento: {})", systemId, sibling);
                return null;
            }
        };
    }

    /** LSInput minimalista respaldado por un InputStream del classpath. */
    private static final class ClasspathLSInput implements LSInput {
        private final String publicId, systemId, baseURI;
        private InputStream stream;
        private String encoding = "UTF-8";

        ClasspathLSInput(String publicId, String systemId, String baseURI, InputStream stream) {
            this.publicId = publicId; this.systemId = systemId; this.baseURI = baseURI; this.stream = stream;
        }
        @Override public Reader getCharacterStream() { return null; }
        @Override public void setCharacterStream(Reader characterStream) {}
        @Override public InputStream getByteStream() { return stream; }
        @Override public void setByteStream(InputStream byteStream) { this.stream = byteStream; }
        @Override public String getStringData() { return null; }
        @Override public void setStringData(String stringData) {}
        @Override public String getSystemId() { return systemId; }
        @Override public void setSystemId(String systemId) {}
        @Override public String getPublicId() { return publicId; }
        @Override public void setPublicId(String publicId) {}
        @Override public String getBaseURI() { return baseURI; }
        @Override public void setBaseURI(String baseURI) {}
        @Override public String getEncoding() { return encoding; }
        @Override public void setEncoding(String encoding) { this.encoding = encoding; }
        @Override public boolean getCertifiedText() { return false; }
        @Override public void setCertifiedText(boolean certifiedText) {}
    }

    /** Excepción de validación XSD. La app la convierte en respuesta clara para el usuario. */
    public static class XsdValidationException extends RuntimeException {
        public XsdValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
