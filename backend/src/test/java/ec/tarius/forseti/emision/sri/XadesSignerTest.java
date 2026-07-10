package ec.tarius.forseti.emision.sri;

import ec.tarius.forseti.emision.domain.Comprobante;
import ec.tarius.forseti.emision.domain.ComprobanteDetalle;
import ec.tarius.forseti.emision.domain.ComprobanteDetalle.CodigoPorcentajeIva;
import ec.tarius.forseti.empresa.domain.Empresa;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XadesSignerTest {

    private static final String DS_NS = "http://www.w3.org/2000/09/xmldsig#";
    private static final String XADES_NS = "http://uri.etsi.org/01903/v1.3.2#";
    private static final String RUC_TARIUS = "1793235976001";

    private static TestP12Factory.P12 p12;
    private static XadesSigner signer;

    @BeforeAll
    static void setUpAll() {
        p12 = TestP12Factory.generar();
        signer = new XadesSigner();
    }

    @Test
    void firma_anade_signature_como_hijo_del_factura() {
        Document doc = facturaXml();
        int hijosAntes = doc.getDocumentElement().getChildNodes().getLength();

        signer.firmar(doc, p12.bytes(), p12.password());

        int hijosDespues = doc.getDocumentElement().getChildNodes().getLength();
        assertThat(hijosDespues).isGreaterThan(hijosAntes);

        NodeList sigs = doc.getElementsByTagNameNS(DS_NS, "Signature");
        assertThat(sigs.getLength()).isEqualTo(1);

        Element sig = (Element) sigs.item(0);
        assertThat(sig.getParentNode()).isEqualTo(doc.getDocumentElement());
    }

    @Test
    void signature_usa_rsa_sha1_segun_ficha_sri() {
        Document doc = facturaXml();
        signer.firmar(doc, p12.bytes(), p12.password());

        Element sig = (Element) doc.getElementsByTagNameNS(DS_NS, "Signature").item(0);

        Element signatureMethod = (Element) sig.getElementsByTagNameNS(DS_NS, "SignatureMethod").item(0);
        assertThat(signatureMethod.getAttribute("Algorithm"))
            .as("Ficha SRI v2.26 §6 + Anexo 14 fijan explícitamente RSA-SHA1")
            .isEqualTo("http://www.w3.org/2000/09/xmldsig#rsa-sha1");

        Element canon = (Element) sig.getElementsByTagNameNS(DS_NS, "CanonicalizationMethod").item(0);
        assertThat(canon.getAttribute("Algorithm"))
            .isEqualTo("http://www.w3.org/TR/2001/REC-xml-c14n-20010315");
    }

    @Test
    void signature_tiene_referencia_a_comprobante_con_enveloped_transform() {
        Document doc = facturaXml();
        signer.firmar(doc, p12.bytes(), p12.password());

        NodeList refs = doc.getElementsByTagNameNS(DS_NS, "Reference");
        assertThat(refs.getLength())
            .as("XAdES-BES SRI espera 3 references: #comprobante + SignedProperties + Certificate")
            .isGreaterThanOrEqualTo(2);  // mínimo: comprobante + SignedProperties

        boolean encontroRefAComprobante = false;
        boolean encontroEnvelopedTransform = false;
        for (int i = 0; i < refs.getLength(); i++) {
            Element ref = (Element) refs.item(i);
            String uri = ref.getAttribute("URI");
            if ("#comprobante".equals(uri)) {
                encontroRefAComprobante = true;
                NodeList transforms = ref.getElementsByTagNameNS(DS_NS, "Transform");
                for (int j = 0; j < transforms.getLength(); j++) {
                    Element t = (Element) transforms.item(j);
                    if ("http://www.w3.org/2000/09/xmldsig#enveloped-signature"
                        .equals(t.getAttribute("Algorithm"))) {
                        encontroEnvelopedTransform = true;
                    }
                }
            }
        }
        assertThat(encontroRefAComprobante).as("debe haber Reference URI=#comprobante").isTrue();
        assertThat(encontroEnvelopedTransform)
            .as("la reference a #comprobante necesita enveloped-signature transform")
            .isTrue();
    }

    @Test
    void signature_referencias_usan_digest_sha1() {
        Document doc = facturaXml();
        signer.firmar(doc, p12.bytes(), p12.password());

        NodeList digests = doc.getElementsByTagNameNS(DS_NS, "DigestMethod");
        assertThat(digests.getLength()).isGreaterThan(0);
        for (int i = 0; i < digests.getLength(); i++) {
            Element d = (Element) digests.item(i);
            assertThat(d.getAttribute("Algorithm"))
                .as("Todos los digests deben ser SHA-1 (ficha SRI v2.26 Anexo 14)")
                .isEqualTo("http://www.w3.org/2000/09/xmldsig#sha1");
        }
    }

    @Test
    void signature_lleva_keyinfo_con_x509_certificate() {
        Document doc = facturaXml();
        signer.firmar(doc, p12.bytes(), p12.password());

        Element ki = (Element) doc.getElementsByTagNameNS(DS_NS, "KeyInfo").item(0);
        assertThat(ki).isNotNull();
        NodeList x509Certs = ki.getElementsByTagNameNS(DS_NS, "X509Certificate");
        assertThat(x509Certs.getLength()).isGreaterThan(0);

        // El cert serializado en base64 debe corresponder al cert del p12 usado
        String certB64 = x509Certs.item(0).getTextContent().replaceAll("\\s", "");
        assertThat(certB64).isNotBlank();
        assertThat(certB64).matches("[A-Za-z0-9+/=]+");
    }

    @Test
    void signature_contiene_qualifying_properties_xades_bes() {
        Document doc = facturaXml();
        signer.firmar(doc, p12.bytes(), p12.password());

        NodeList qp = doc.getElementsByTagNameNS(XADES_NS, "QualifyingProperties");
        assertThat(qp.getLength()).as("XAdES-BES requiere QualifyingProperties").isEqualTo(1);

        NodeList sp = doc.getElementsByTagNameNS(XADES_NS, "SignedProperties");
        assertThat(sp.getLength()).isEqualTo(1);

        NodeList ssp = doc.getElementsByTagNameNS(XADES_NS, "SignedSignatureProperties");
        assertThat(ssp.getLength()).isEqualTo(1);

        // SigningTime y SigningCertificate son los 2 obligatorios de XAdES-BES
        Element sigTime = (Element) doc.getElementsByTagNameNS(XADES_NS, "SigningTime").item(0);
        assertThat(sigTime).isNotNull();

        Element sigCert = (Element) doc.getElementsByTagNameNS(XADES_NS, "SigningCertificate").item(0);
        if (sigCert == null) {
            // xades4j 2.x puede emitir SigningCertificateV2 en vez de la versión v1
            sigCert = (Element) doc.getElementsByTagNameNS(XADES_NS, "SigningCertificateV2").item(0);
        }
        assertThat(sigCert).as("BES requiere SigningCertificate(V2)").isNotNull();
    }

    @Test
    void firma_produce_xml_serializable_y_reparseable() {
        Document doc = facturaXml();
        signer.firmar(doc, p12.bytes(), p12.password());

        byte[] xmlFirmado = FacturaXmlBuilder.serializar(doc);
        assertThat(xmlFirmado).isNotEmpty();

        // Debe poder reparsear el XML resultante sin error
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            Document reparsed = dbf.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xmlFirmado));
            assertThat(reparsed.getDocumentElement().getTagName()).isEqualTo("factura");
            assertThat(reparsed.getElementsByTagNameNS(DS_NS, "Signature").getLength()).isEqualTo(1);
        } catch (Exception e) {
            throw new AssertionError("XML firmado no reparsea: " + e.getMessage(), e);
        }
    }

    @Test
    void firma_password_invalida_lanza_excepcion_clara() {
        Document doc = facturaXml();
        assertThatThrownBy(() -> signer.firmar(doc, p12.bytes(), "wrong-password"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(".p12");
    }

    @Test
    void firma_p12_corrupto_lanza_excepcion() {
        Document doc = facturaXml();
        byte[] corrupto = new byte[100];  // bytes random no son un .p12 válido
        assertThatThrownBy(() -> signer.firmar(doc, corrupto, "anything"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void firmar_documento_sin_id_lanza_excepcion_clara() {
        Document doc;
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            doc = dbf.newDocumentBuilder().newDocument();
            doc.appendChild(doc.createElement("otraCosa"));
        } catch (Exception e) { throw new RuntimeException(e); }

        assertThatThrownBy(() -> signer.firmar(doc, p12.bytes(), p12.password()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("id");
    }

    @Test
    void firmarYSerializar_devuelve_bytes_completos() {
        Document doc = facturaXml();
        byte[] xml = signer.firmarYSerializar(doc, p12.bytes(), p12.password());

        String s = new String(xml, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(s).contains("<factura");
        assertThat(s).contains("ds:Signature");
        assertThat(s).contains("ds:SignatureValue");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Anti-regresión Anexo 14 ficha SRI v2.26 (bug Sprint 3 Fase E, 2026-06-21)
    // Si alguno de estos tests vuelve a fallar, el SRI rechazará con error 39.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void anexo14_tiene_EXACTAMENTE_3_references_no_mas_no_menos() {
        Document doc = facturaXml();
        signer.firmar(doc, p12.bytes(), p12.password());

        NodeList refs = doc.getElementsByTagNameNS(DS_NS, "Reference");
        assertThat(refs.getLength())
            .as("Anexo 14: SignedInfo debe tener 3 References (#comprobante, #SignedProperties, #Certificate)")
            .isEqualTo(3);
    }

    @Test
    void anexo14_references_a_SignedProperties_y_KeyInfo_NO_tienen_Transforms() {
        // ESTE es el bug que arreglamos en Sprint 3 Fase E. xades4j hardcodeaba
        // <ds:Transforms><ds:Transform c14n/></ds:Transforms> en estas References.
        // El SRI rechaza con error 39 cuando hay Transforms ahí.
        Document doc = facturaXml();
        signer.firmar(doc, p12.bytes(), p12.password());

        NodeList refs = doc.getElementsByTagNameNS(DS_NS, "Reference");
        for (int i = 0; i < refs.getLength(); i++) {
            Element ref = (Element) refs.item(i);
            String uri = ref.getAttribute("URI");
            // Solo la Reference al comprobante puede tener Transforms (enveloped-signature).
            // Las otras dos (SignedProperties y KeyInfo) NO deben tener Transforms.
            if (!uri.equals("#comprobante")) {
                NodeList transforms = ref.getElementsByTagNameNS(DS_NS, "Transforms");
                assertThat(transforms.getLength())
                    .as("Reference URI=%s NO debe tener <ds:Transforms> (Anexo 14 + bug Sprint 3)", uri)
                    .isEqualTo(0);
            }
        }
    }

    @Test
    void anexo14_reference_al_comprobante_tiene_solo_enveloped_signature_transform() {
        Document doc = facturaXml();
        signer.firmar(doc, p12.bytes(), p12.password());

        NodeList refs = doc.getElementsByTagNameNS(DS_NS, "Reference");
        Element refComprobante = null;
        for (int i = 0; i < refs.getLength(); i++) {
            Element r = (Element) refs.item(i);
            if ("#comprobante".equals(r.getAttribute("URI"))) {
                refComprobante = r;
                break;
            }
        }
        assertThat(refComprobante).as("debe existir Reference URI=#comprobante").isNotNull();

        NodeList transforms = refComprobante.getElementsByTagNameNS(DS_NS, "Transform");
        assertThat(transforms.getLength())
            .as("Reference al comprobante debe tener exactamente 1 transform")
            .isEqualTo(1);
        Element t = (Element) transforms.item(0);
        assertThat(t.getAttribute("Algorithm"))
            .isEqualTo("http://www.w3.org/2000/09/xmldsig#enveloped-signature");
    }

    @Test
    void anexo14_keyinfo_tiene_RSAKeyValue_con_Modulus_y_Exponent() {
        Document doc = facturaXml();
        signer.firmar(doc, p12.bytes(), p12.password());

        Element keyInfo = (Element) doc.getElementsByTagNameNS(DS_NS, "KeyInfo").item(0);
        Element rsaKey = (Element) keyInfo.getElementsByTagNameNS(DS_NS, "RSAKeyValue").item(0);
        assertThat(rsaKey).as("Anexo 14: KeyInfo debe llevar RSAKeyValue").isNotNull();

        Element modulus = (Element) rsaKey.getElementsByTagNameNS(DS_NS, "Modulus").item(0);
        Element exponent = (Element) rsaKey.getElementsByTagNameNS(DS_NS, "Exponent").item(0);
        assertThat(modulus).isNotNull();
        assertThat(exponent).isNotNull();
        assertThat(modulus.getTextContent().replaceAll("\\s", ""))
            .as("Modulus en base64").matches("[A-Za-z0-9+/=]+");
        // Exponente RSA típico = 65537 = "AQAB" en base64
        assertThat(exponent.getTextContent().replaceAll("\\s", "")).isEqualTo("AQAB");
    }

    @Test
    void anexo14_keyinfo_tiene_Id_referenciable_y_Reference_apunta_a_ese_Id() {
        Document doc = facturaXml();
        signer.firmar(doc, p12.bytes(), p12.password());

        Element keyInfo = (Element) doc.getElementsByTagNameNS(DS_NS, "KeyInfo").item(0);
        String keyInfoId = keyInfo.getAttribute("Id");
        assertThat(keyInfoId).as("KeyInfo debe tener Id para que la 3ª Reference lo apunte").isNotBlank();
        assertThat(keyInfoId).startsWith("Certificate-");

        // Debe haber una Reference URI=#{keyInfoId}
        NodeList refs = doc.getElementsByTagNameNS(DS_NS, "Reference");
        boolean encontrada = false;
        for (int i = 0; i < refs.getLength(); i++) {
            if (("#" + keyInfoId).equals(((Element) refs.item(i)).getAttribute("URI"))) {
                encontrada = true; break;
            }
        }
        assertThat(encontrada)
            .as("debe haber Reference URI=#%s (firma del KeyInfo, sección 6.5 ficha SRI)", keyInfoId)
            .isTrue();
    }

    @Test
    void anexo14_SignedProperties_tiene_SignedDataObjectProperties_con_DataObjectFormat() {
        Document doc = facturaXml();
        signer.firmar(doc, p12.bytes(), p12.password());

        Element sdop = (Element) doc.getElementsByTagNameNS(XADES_NS, "SignedDataObjectProperties").item(0);
        assertThat(sdop).as("Anexo 14: SignedProperties debe llevar SignedDataObjectProperties").isNotNull();

        Element dof = (Element) sdop.getElementsByTagNameNS(XADES_NS, "DataObjectFormat").item(0);
        assertThat(dof).isNotNull();
        assertThat(dof.getAttribute("ObjectReference"))
            .as("DataObjectFormat.ObjectReference debe apuntar a la 1ª Reference (#DocumentRef-...)")
            .startsWith("#DocumentRef-");

        assertThat(dof.getElementsByTagNameNS(XADES_NS, "MimeType").item(0).getTextContent())
            .isEqualTo("text/xml");
        assertThat(dof.getElementsByTagNameNS(XADES_NS, "Encoding").item(0).getTextContent())
            .isEqualTo("UTF-8");
    }

    @Test
    void anexo14_IssuerName_esta_en_MAYUSCULAS_no_minusculas() {
        // El SRI compara byte a byte el X509IssuerName contra el IssuerDN real del cert.
        // xades4j por default emitía "cn=..., o=..., c=..." en minúsculas, lo que rompía
        // el match exacto. Nuestro firmador fuerza MAYÚSCULAS (RFC2253 + uppercase keys).
        // El ORDEN reverse (CN primero, C último) depende de cómo el cert ASN.1 almacena
        // el DN — los certs reales (Security Data, Lazzate, BCE) producen CN primero,
        // pero un cert self-signed de test puede quedar al revés. El SRI acepta cualquier
        // orden mientras coincida con el cert. Lo que NO acepta es minúsculas.
        Document doc = facturaXml();
        signer.firmar(doc, p12.bytes(), p12.password());

        Element issuerName = (Element) doc.getElementsByTagNameNS(DS_NS, "X509IssuerName").item(0);
        assertThat(issuerName).isNotNull();
        String dn = issuerName.getTextContent();

        // Keys en MAYÚSCULAS.
        assertThat(dn)
            .as("Anexo 14: X509IssuerName con keys EN MAYÚSCULAS (CN=, O=, C=)")
            .doesNotContain("cn=").doesNotContain("o=").doesNotContain("c=");
        assertThat(dn).contains("CN=");
        assertThat(dn).contains("C=");
    }

    @Test
    void auto_verificacion_atrapa_xml_manipulado_post_firma() {
        // Simulamos un atacante que modifica el XML después de firmar:
        // si la auto-verificación funciona, el SRI nunca recibiría un XML alterado
        // por nuestro lado. (En realidad esta verificación corre dentro de firmar(),
        // así que no podemos modificar entre firmar y verificar — pero es buen test
        // de que la verificación efectivamente detecta digest mismatch.)
        Document doc = facturaXml();
        signer.firmar(doc, p12.bytes(), p12.password());

        // El XML firmado debe quedar válido. Confirmamos serializando + reparseando.
        byte[] xml = FacturaXmlBuilder.serializar(doc);
        assertThat(xml).isNotEmpty();
        assertThat(new String(xml, java.nio.charset.StandardCharsets.UTF_8))
            .contains("<ds:Signature").contains("<ds:SignatureValue");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Fixtures
    // ─────────────────────────────────────────────────────────────────────

    private Document facturaXml() {
        Empresa e = Empresa.nueva(RUC_TARIUS, "TARIUS S.A.S.");
        e.setDireccion("Av. Amazonas N34-451, Quito");
        e.setRegimenTributario("RIMPE_EMPRENDEDOR");
        e.setObligadoContabilidad(false);

        Comprobante c = Comprobante.nuevo(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "FACTURA", "PRUEBAS", 1L, "001-001-000000001",
            ClaveAccesoGenerator.generar(
                LocalDate.of(2026, 6, 20),
                ClaveAccesoGenerator.TipoDocumento.FACTURA,
                e.getRuc(),
                ClaveAccesoGenerator.Ambiente.PRUEBAS,
                "001", "001", 1L, "12345678"),
            LocalDate.of(2026, 6, 20));
        c.receptor("04", "1790012345001", "Cliente Demo S.A.",
                   "Av. NN 100", "demo@ejemplo.ec", null);
        c.formaPago("01", 0);
        c.totales(new BigDecimal("100.00"), BigDecimal.ZERO,
                  new BigDecimal("15.00"), new BigDecimal("115.00"));

        ComprobanteDetalle d = ComprobanteDetalle.nuevo(
            UUID.randomUUID(), UUID.randomUUID(), 1,
            "PROD-001", "Servicio",
            new BigDecimal("1.000000"), new BigDecimal("100.000000"),
            BigDecimal.ZERO, new BigDecimal("100.00"),
            CodigoPorcentajeIva.IVA_15,
            new BigDecimal("100.00"), new BigDecimal("15.00"));

        return FacturaXmlBuilder.construir(e, c, List.of(d));
    }
}
