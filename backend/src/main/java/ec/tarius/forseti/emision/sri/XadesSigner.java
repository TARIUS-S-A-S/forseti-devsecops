package ec.tarius.forseti.emision.sri;

import org.apache.xml.security.Init;
import org.apache.xml.security.c14n.Canonicalizer;
import org.apache.xml.security.signature.Reference;
import org.apache.xml.security.signature.SignedInfo;
import org.apache.xml.security.signature.XMLSignature;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.security.auth.x500.X500Principal;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigInteger;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Security;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Enumeration;
import java.util.UUID;

/**
 * Firma XAdES-BES para comprobantes SRI Ecuador.
 *
 * Implementación MANUAL con templates de string + Apache Santuario para canonicalización,
 * portada del SDK TypeScript `bryancalisto/ec-sri-invoice-signer` (probado contra SRI real).
 *
 * Por qué NO se usa xades4j:
 *   xades4j inserta <ds:Transforms><ds:Transform c14n/></ds:Transforms> en las References
 *   de SignedProperties y KeyInfo. El Anexo 14 de la ficha técnica SRI v2.26 muestra esas
 *   References SIN Transforms (solo DigestMethod + DigestValue). El validador WS del SRI
 *   rechaza con error 39 "FIRMA INVÁLIDA — certificado firmante no es válido" (mensaje
 *   engañoso: en realidad no pudo verificar la firma cripto) si las References llevan
 *   Transforms. Tampoco hay forma en xades4j de pedirle "no agregues Transforms" en esas
 *   References — son hardcoded internamente.
 *
 * Algoritmos (ficha SRI v2.26 §6 + Anexo 14):
 *   - SignatureMethod = RSA-SHA1
 *   - DigestMethod = SHA-1 (en TODAS las references y en CertDigest)
 *   - CanonicalizationMethod = c14n estándar sin comentarios (REC-xml-c14n-20010315)
 *   - Transform en Reference#comprobante = enveloped-signature (la única transform)
 *
 * Estructura del XML producido (idéntica al Anexo 14 SRI):
 *   <ds:Signature Id="Signature-{uuid}">
 *     <ds:SignedInfo Id="SignedInfo-{uuid}">
 *       <ds:CanonicalizationMethod/>
 *       <ds:SignatureMethod RSA-SHA1/>
 *       Ref 1: URI="#comprobante" CON Transforms→enveloped, DigestMethod SHA1, DigestValue
 *       Ref 2: Type=SignedProperties URI=#SignedProperties-{uuid} SIN Transforms
 *       Ref 3: URI=#Certificate-{uuid} (KeyInfo) SIN Transforms
 *     </ds:SignedInfo>
 *     <ds:SignatureValue/>
 *     <ds:KeyInfo Id="Certificate-{uuid}">X509Certificate + RSAKeyValue (Modulus+Exponent)</ds:KeyInfo>
 *     <ds:Object>
 *       <xades:QualifyingProperties>
 *         <xades:SignedProperties Id="SignedProperties-{uuid}">
 *           SigningTime, SigningCertificate(Cert+CertDigest+IssuerSerial),
 *           SignedDataObjectProperties(DataObjectFormat→#Ref1)
 *         </xades:SignedProperties>
 *       </xades:QualifyingProperties>
 *     </ds:Object>
 *   </ds:Signature>
 */
@Component
public class XadesSigner {

    private static final Logger log = LoggerFactory.getLogger(XadesSigner.class);

    private static final String NS_DS    = "http://www.w3.org/2000/09/xmldsig#";
    private static final String NS_XADES = "http://uri.etsi.org/01903/v1.3.2#";

    private static final String ALGO_C14N      = "http://www.w3.org/TR/2001/REC-xml-c14n-20010315";
    private static final String ALGO_RSA_SHA1  = "http://www.w3.org/2000/09/xmldsig#rsa-sha1";
    private static final String ALGO_SHA1      = "http://www.w3.org/2000/09/xmldsig#sha1";
    private static final String ALGO_ENVELOPED = "http://www.w3.org/2000/09/xmldsig#enveloped-signature";
    private static final String TYPE_SIGNED_PROPERTIES = "http://uri.etsi.org/01903#SignedProperties";

    /** Atributo id del elemento que se firma (en la factura SRI: <factura id="comprobante">). */
    public static final String ID_COMPROBANTE = "comprobante";

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        if (!Init.isInitialized()) {
            Init.init();
        }
    }

    /**
     * Firma el documento en-place. Después de esta llamada, el {@code doc.getDocumentElement()}
     * contendrá un nuevo hijo {@code <ds:Signature>} con la firma XAdES-BES completa.
     *
     * @param doc         documento XML del comprobante (ya generado por FacturaXmlBuilder)
     * @param p12Bytes    bytes del archivo .p12 en memoria
     * @param p12Password contraseña del .p12
     */
    public void firmar(Document doc, byte[] p12Bytes, String p12Password) {
        Element raiz = doc.getDocumentElement();
        if (!raiz.hasAttribute("id")) {
            throw new IllegalStateException(
                "El elemento raíz no tiene atributo id — XadesSigner requiere <"
                + raiz.getTagName() + " id=\"" + ID_COMPROBANTE + "\">");
        }
        raiz.setIdAttribute("id", true);

        try {
            ClaveYCert kc = cargarP12(p12Bytes, p12Password);

            // IDs únicos para el XML firmado (mismo patrón que el SDK bryancalisto).
            String docRefId            = "DocumentRef-"        + uuid();
            String keyInfoId           = "Certificate-"        + uuid();
            String keyInfoRefId        = "CertificateRef-"     + uuid();
            String signedInfoId        = "SignedInfo-"         + uuid();
            String signedPropsRefId    = "SignedPropertiesRef-"+ uuid();
            String signedPropsId       = "SignedProperties-"   + uuid();
            String signatureId         = "Signature-"          + uuid();
            String signatureObjectId   = "SignatureObject-"    + uuid();
            String signatureValueId    = "SignatureValue-"     + uuid();

            // Cert info (cert leaf).
            X509Certificate cert = kc.cert;
            byte[] certDer = cert.getEncoded();
            String certB64 = b64(certDer);
            String certSha1B64 = b64(sha1(certDer));
            String issuerName = issuerNameSri(cert);
            String serial = cert.getSerialNumber().toString();
            RSAPublicKey pub = (RSAPublicKey) cert.getPublicKey();
            String modulusB64  = b64(unsignedBytes(pub.getModulus()));
            String exponentB64 = b64(unsignedBytes(pub.getPublicExponent()));

            // Templates.
            String keyInfoXml = buildKeyInfo(keyInfoId, certB64, modulusB64, exponentB64);
            String signingTime = ZonedDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
            String signedPropsXml = buildSignedProperties(
                signedPropsId, signingTime, certSha1B64, issuerName, serial, docRefId);

            // Hashes (canonicalizando cada sub-tree con sus namespaces heredados).
            //
            // a) Hash del comprobante: con enveloped-signature transform = el documento sin
            //    la Signature. Como aún no insertamos la Signature en el DOM, basta canonicalizar
            //    el root tal cual.
            String docHashB64 = b64(sha1(canonicalize(raiz)));

            // b) Hash del SignedProperties: lo wrappeamos en un root con xmlns:ds y xmlns:xades
            //    para que la canonicalización agregue esos namespaces tal como los heredaría
            //    cuando esté embebido en <ds:Signature>...<xades:QualifyingProperties>...</xades:>
            String signedPropsHashB64 = b64(sha1(canonicalize(wrap(signedPropsXml,
                "xmlns:ds=\"" + NS_DS + "\" xmlns:xades=\"" + NS_XADES + "\""))));

            // c) Hash del KeyInfo: heredando xmlns:ds.
            String keyInfoHashB64 = b64(sha1(canonicalize(wrap(keyInfoXml,
                "xmlns:ds=\"" + NS_DS + "\""))));

            // SignedInfo con los 3 hashes.
            String signedInfoXml = buildSignedInfo(
                signedInfoId,
                docRefId, docHashB64,
                signedPropsRefId, signedPropsId, signedPropsHashB64,
                keyInfoRefId, keyInfoId, keyInfoHashB64);

            // Firmar el SignedInfo canonicalizado con RSA-SHA1.
            byte[] signedInfoC14n = canonicalize(wrap(signedInfoXml,
                "xmlns:ds=\"" + NS_DS + "\""));
            String signatureValue = b64(signRsaSha1(signedInfoC14n, kc.privateKey));

            // Signature completa.
            String signatureXml = buildSignature(
                signatureId, signatureObjectId, signatureValueId,
                signedInfoXml, signatureValue, keyInfoXml, signedPropsXml);

            // Insertar la Signature como último child del root del documento original.
            Element signatureElem = parseFragment(signatureXml);
            Node imported = doc.importNode(signatureElem, true);
            raiz.appendChild(imported);

            // POSTCONDICIÓN: la firma que acabamos de generar DEBE poder validarse criptográficamente
            // con un validador W3C estándar. Si no, el bug es nuestro (firmador roto), no del SRI.
            // Esta verificación atrapa regresiones del fix de Sprint 3 Fase E ANTES de mandar al SRI.
            autoVerificar(doc, kc.cert);

            log.debug("Comprobante {} firmado con XAdES-BES (RSA-SHA1, c14n) y auto-verificado",
                raiz.getAttribute("id"));
        } catch (FirmaException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new FirmaException("No se pudo firmar el comprobante: " + e.getMessage(), e);
        }
    }

    /** Helper para tests / flow integrado: serializa el documento firmado a bytes UTF-8. */
    public byte[] firmarYSerializar(Document doc, byte[] p12Bytes, String p12Password) {
        firmar(doc, p12Bytes, p12Password);
        return FacturaXmlBuilder.serializar(doc);
    }

    // ---------- templates XML (idénticos al SDK bryancalisto) ----------

    private static String buildKeyInfo(String id, String certB64,
                                       String modulusB64, String exponentB64) {
        return "<ds:KeyInfo Id=\"" + id + "\">"
            + "<ds:X509Data>"
            + "<ds:X509Certificate>" + certB64 + "</ds:X509Certificate>"
            + "</ds:X509Data>"
            + "<ds:KeyValue>"
            + "<ds:RSAKeyValue>"
            + "<ds:Modulus>" + modulusB64 + "</ds:Modulus>"
            + "<ds:Exponent>" + exponentB64 + "</ds:Exponent>"
            + "</ds:RSAKeyValue>"
            + "</ds:KeyValue>"
            + "</ds:KeyInfo>";
    }

    private static String buildSignedProperties(String id, String signingTime,
                                                String certSha1B64, String issuerName,
                                                String serial, String docRefId) {
        return "<xades:SignedProperties Id=\"" + id + "\">"
            + "<xades:SignedSignatureProperties>"
            + "<xades:SigningTime>" + signingTime + "</xades:SigningTime>"
            + "<xades:SigningCertificate>"
            + "<xades:Cert>"
            + "<xades:CertDigest>"
            + "<ds:DigestMethod Algorithm=\"" + ALGO_SHA1 + "\"/>"
            + "<ds:DigestValue>" + certSha1B64 + "</ds:DigestValue>"
            + "</xades:CertDigest>"
            + "<xades:IssuerSerial>"
            + "<ds:X509IssuerName>" + issuerName + "</ds:X509IssuerName>"
            + "<ds:X509SerialNumber>" + serial + "</ds:X509SerialNumber>"
            + "</xades:IssuerSerial>"
            + "</xades:Cert>"
            + "</xades:SigningCertificate>"
            + "</xades:SignedSignatureProperties>"
            + "<xades:SignedDataObjectProperties>"
            + "<xades:DataObjectFormat ObjectReference=\"#" + docRefId + "\">"
            + "<xades:Description>Firma digital</xades:Description>"
            + "<xades:MimeType>text/xml</xades:MimeType>"
            + "<xades:Encoding>UTF-8</xades:Encoding>"
            + "</xades:DataObjectFormat>"
            + "</xades:SignedDataObjectProperties>"
            + "</xades:SignedProperties>";
    }

    private static String buildSignedInfo(String signedInfoId,
                                          String docRefId, String docHash,
                                          String signedPropsRefId, String signedPropsId, String signedPropsHash,
                                          String keyInfoRefId, String keyInfoId, String keyInfoHash) {
        return "<ds:SignedInfo Id=\"" + signedInfoId + "\">"
            + "<ds:CanonicalizationMethod Algorithm=\"" + ALGO_C14N + "\"/>"
            + "<ds:SignatureMethod Algorithm=\"" + ALGO_RSA_SHA1 + "\"/>"
            + "<ds:Reference Id=\"" + docRefId + "\" URI=\"#" + ID_COMPROBANTE + "\">"
            + "<ds:Transforms>"
            + "<ds:Transform Algorithm=\"" + ALGO_ENVELOPED + "\"/>"
            + "</ds:Transforms>"
            + "<ds:DigestMethod Algorithm=\"" + ALGO_SHA1 + "\"/>"
            + "<ds:DigestValue>" + docHash + "</ds:DigestValue>"
            + "</ds:Reference>"
            + "<ds:Reference Id=\"" + signedPropsRefId + "\""
            + " Type=\"" + TYPE_SIGNED_PROPERTIES + "\""
            + " URI=\"#" + signedPropsId + "\">"
            + "<ds:DigestMethod Algorithm=\"" + ALGO_SHA1 + "\"/>"
            + "<ds:DigestValue>" + signedPropsHash + "</ds:DigestValue>"
            + "</ds:Reference>"
            + "<ds:Reference Id=\"" + keyInfoRefId + "\" URI=\"#" + keyInfoId + "\">"
            + "<ds:DigestMethod Algorithm=\"" + ALGO_SHA1 + "\"/>"
            + "<ds:DigestValue>" + keyInfoHash + "</ds:DigestValue>"
            + "</ds:Reference>"
            + "</ds:SignedInfo>";
    }

    private static String buildSignature(String signatureId, String signatureObjectId,
                                         String signatureValueId, String signedInfoXml,
                                         String signatureValueB64, String keyInfoXml,
                                         String signedPropsXml) {
        return "<ds:Signature xmlns:ds=\"" + NS_DS + "\" Id=\"" + signatureId + "\">"
            + signedInfoXml
            + "<ds:SignatureValue Id=\"" + signatureValueId + "\">" + signatureValueB64 + "</ds:SignatureValue>"
            + keyInfoXml
            + "<ds:Object Id=\"" + signatureObjectId + "\">"
            + "<xades:QualifyingProperties xmlns:xades=\"" + NS_XADES + "\" Target=\"#" + signatureId + "\">"
            + signedPropsXml
            + "</xades:QualifyingProperties>"
            + "</ds:Object>"
            + "</ds:Signature>";
    }

    // ---------- helpers crypto / xml ----------

    private static ClaveYCert cargarP12(byte[] p12Bytes, String password) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12", "BC");
            ks.load(new ByteArrayInputStream(p12Bytes), password.toCharArray());
            String alias = null;
            for (Enumeration<String> a = ks.aliases(); a.hasMoreElements();) {
                String name = a.nextElement();
                if (ks.isKeyEntry(name)) { alias = name; break; }
            }
            if (alias == null) {
                throw new IllegalArgumentException(
                    "El .p12 no contiene una entrada con clave privada");
            }
            PrivateKey pk = (PrivateKey) ks.getKey(alias, password.toCharArray());
            X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
            return new ClaveYCert(pk, cert);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "No se pudo abrir el .p12 (contraseña incorrecta o archivo corrupto): "
                + e.getClass().getSimpleName(), e);
        }
    }

    private static byte[] canonicalize(Node n) throws Exception {
        Canonicalizer c = Canonicalizer.getInstance(Canonicalizer.ALGO_ID_C14N_OMIT_COMMENTS);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        c.canonicalizeSubtree(n, out);
        return out.toByteArray();
    }

    /**
     * Parsea un fragmento XML envolviéndolo en un root con los namespaces declarados como atributos,
     * y devuelve el primer hijo (el elemento real). Sirve para canonicalizar sub-elementos con
     * namespaces heredados sin tener que insertarlos en el DOM final aún.
     */
    private static Element wrap(String xml, String nsAttrs) throws Exception {
        String wrapped = "<wrapper " + nsAttrs + ">" + xml + "</wrapper>";
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        DocumentBuilder b = f.newDocumentBuilder();
        Document d = b.parse(new InputSource(new StringReader(wrapped)));
        NodeList children = d.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c.getNodeType() == Node.ELEMENT_NODE) {
                return (Element) c;
            }
        }
        throw new IllegalStateException("wrap: no se encontró elemento dentro del wrapper");
    }

    /** Parsea un fragmento XML standalone (ya con sus xmlns: declarados) y devuelve el root. */
    private static Element parseFragment(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        DocumentBuilder b = f.newDocumentBuilder();
        Document d = b.parse(new InputSource(new StringReader(xml)));
        return d.getDocumentElement();
    }

    private static byte[] sha1(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-1").digest(data);
    }

    private static byte[] signRsaSha1(byte[] data, PrivateKey key) throws Exception {
        Signature s = Signature.getInstance("SHA1withRSA");
        s.initSign(key);
        s.update(data);
        return s.sign();
    }

    private static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    /**
     * Quita el byte 0x00 inicial que BigInteger.toByteArray() agrega para preservar el signo
     * positivo en two's complement. XMLDSig RSAKeyValue espera el unsigned magnitude.
     */
    private static byte[] unsignedBytes(BigInteger bi) {
        byte[] bytes = bi.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    /**
     * IssuerName formateado al estilo SRI: CN va primero, C al final. Ej:
     *   "CN=AUTORIDAD..., OU=..., O=..., C=EC" (con espacio después de la coma, mayúsculas).
     * X500Principal.getName(RFC2253) hace el reverse (más específico primero) — bien — pero
     * SIN espacio y con lowercase para keys conocidas. Acá se normaliza a mayúsculas y
     * espacio post-coma para coincidir con el formato del Anexo 14 SRI.
     */
    private static String issuerNameSri(X509Certificate cert) {
        X500Principal issuer = cert.getIssuerX500Principal();
        // RFC2253 invierte el orden (CN primero, C último). Sin espacios después de comas.
        String rfc2253 = issuer.getName(X500Principal.RFC2253);
        // Reemplazar keys lowercase por uppercase (cn= → CN=, etc.).
        String[] parts = rfc2253.split("(?<!\\\\),");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(",");
            String part = parts[i].trim();
            int eq = part.indexOf('=');
            if (eq > 0) {
                String key = part.substring(0, eq).toUpperCase();
                String val = part.substring(eq + 1);
                sb.append(key).append("=").append(val);
            } else {
                sb.append(part);
            }
        }
        return sb.toString();
    }

    /**
     * Auto-verificación criptográfica de la firma recién generada. Usa Apache Santuario para
     * validar SignatureValue contra la public key del cert y recalcular cada DigestValue.
     *
     * Si esta verificación falla:
     *   - el bug está en el firmador (no en el SRI),
     *   - lanzamos FirmaException ANTES de mandar al SRI,
     *   - el comprobante NO llega a estado FIRMADA, queda en BORRADOR para reintentar.
     *
     * Si pasa pero el SRI rechaza con error 39, sabemos con certeza que cambió algo del SRI
     * o de su política y necesitamos investigar de su lado, no del nuestro.
     */
    private static void autoVerificar(Document doc, X509Certificate cert) {
        try {
            marcarIdsParaValidacion(doc);

            NodeList sigs = doc.getElementsByTagNameNS(NS_DS, "Signature");
            if (sigs.getLength() == 0) {
                throw new FirmaException(
                    "Auto-verificación: no se encontró <ds:Signature> en el documento firmado", null);
            }
            Element sigElem = (Element) sigs.item(0);

            XMLSignature xmlSig = new XMLSignature(sigElem, "");
            boolean valido = xmlSig.checkSignatureValue(cert);
            if (!valido) {
                // Identificar qué Reference falló (digest mismatch) para dar mensaje útil.
                SignedInfo si = xmlSig.getSignedInfo();
                for (int i = 0; i < si.getLength(); i++) {
                    Reference ref = si.item(i);
                    if (!ref.verify()) {
                        throw new FirmaException(
                            "Auto-verificación: digest de Reference URI=" + ref.getURI()
                            + " no coincide. Bug en el firmador (canonicalización o cálculo de hash), "
                            + "NO en el SRI.", null);
                    }
                }
                throw new FirmaException(
                    "Auto-verificación: SignatureValue no valida con la public key del cert. "
                    + "Bug en el firmador (firma RSA mal calculada), NO en el SRI.", null);
            }
        } catch (FirmaException e) {
            throw e;
        } catch (Exception e) {
            throw new FirmaException(
                "Auto-verificación: error inesperado al validar la firma — " + e.getMessage(), e);
        }
    }

    /**
     * Marca como ID los atributos {@code Id} de los elementos firmados, para que las URIs
     * tipo {@code #SignedProperties-xxx} resuelvan al elemento real durante validate().
     * Sin esto, Santuario no puede resolver las referencias internas y reporta digest mismatch.
     */
    private static void marcarIdsParaValidacion(Document doc) {
        for (String tagDs : new String[]{"Signature", "SignedInfo", "Reference",
                                          "SignatureValue", "KeyInfo", "Object"}) {
            NodeList nodes = doc.getElementsByTagNameNS(NS_DS, tagDs);
            for (int i = 0; i < nodes.getLength(); i++) {
                Element e = (Element) nodes.item(i);
                if (e.hasAttribute("Id")) e.setIdAttribute("Id", true);
            }
        }
        NodeList sps = doc.getElementsByTagNameNS(NS_XADES, "SignedProperties");
        for (int i = 0; i < sps.getLength(); i++) {
            Element e = (Element) sps.item(i);
            if (e.hasAttribute("Id")) e.setIdAttribute("Id", true);
        }
    }

    private record ClaveYCert(PrivateKey privateKey, X509Certificate cert) {}

    public static class FirmaException extends RuntimeException {
        public FirmaException(String mensaje, Throwable causa) { super(mensaje, causa); }
    }
}
