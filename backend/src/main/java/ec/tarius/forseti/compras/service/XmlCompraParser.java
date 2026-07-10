package ec.tarius.forseti.compras.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Parser de XML SRI Ecuador (factura recibida) → datos para autollenar una {@link
 * ec.tarius.forseti.compras.domain.Compra}. HU-F2 gate ①.
 *
 * Maneja dos formatos:
 *
 *   <ol>
 *     <li>Envoltura {@code <autorizacion>} del SRI (lo que devuelve consulta o lo
 *         que el proveedor SRI te entrega como "XML autorizado"): el comprobante
 *         viene como {@code <comprobante>CDATA</comprobante>}.</li>
 *     <li>Comprobante "pelado": el elemento raíz es directamente {@code <factura>}
 *         (con o sin {@code <ds:Signature>}).</li>
 *   </ol>
 *
 * Solo soporta {@code factura} (codDoc=01) por ahora. NC/ND recibidas → Sprint 6.
 *
 * Seguridad: XXE-safe (DTD deshabilitado + no external entities). Crítico porque
 * estamos parseando XML que llega del usuario.
 */
@Component
public class XmlCompraParser {

    private static final Logger log = LoggerFactory.getLogger(XmlCompraParser.class);

    private static final DateTimeFormatter FECHA_SRI = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * Resultado del parseo — datos planos listos para autollenar el form de Compra.
     * {@code fechaAutorizacion} solo se setea si el XML vino envuelto en {@code <autorizacion>}
     * con el timestamp real del SRI. Si vino el comprobante "pelado", queda null y el caller
     * decide (típicamente usar el momento de carga).
     */
    public record DatosCompra(
        String proveedorRuc,
        String proveedorRazonSocial,
        String proveedorDireccion,
        String tipoDocumento,            // FACTURA, NOTA_CREDITO, ...
        String numeroComprobante,         // "001-001-000000001"
        String claveAcceso,               // 49 dígitos (si está autorizado)
        LocalDate fechaEmision,
        Instant fechaAutorizacion,        // del <autorizacion><fechaAutorizacion>, null si pelado
        BigDecimal baseIva15,
        BigDecimal baseIva0,
        BigDecimal baseNoObjeto,
        BigDecimal baseExento,
        BigDecimal valorIva15,
        BigDecimal importeTotal,
        String moneda
    ) {}

    public DatosCompra parsear(byte[] xmlBytes) {
        Document doc = parsearXml(xmlBytes);
        Element root = doc.getDocumentElement();

        // Caso 1: viene envuelto en <autorizacion>...<comprobante>CDATA</comprobante>
        if ("autorizacion".equals(root.getLocalName()) || "autorizacion".equals(root.getNodeName())) {
            String comprobanteCdata = textoDirecto(root, "comprobante");
            if (comprobanteCdata == null || comprobanteCdata.isBlank()) {
                throw new XmlParserException("La envoltura <autorizacion> no contiene <comprobante>");
            }
            // Extraer fechaAutorizacion REAL del SRI (no usar Instant.now() en el caller — eso miente)
            Instant fechaAut = textoOpcional(root, "fechaAutorizacion")
                .map(XmlCompraParser::parsearFechaAutorizacion)
                .orElse(null);

            // Re-parsear el comprobante interno
            Document doc2 = parsearXml(comprobanteCdata.trim());
            DatosCompra base = extraerDeRaizComprobante(doc2.getDocumentElement());
            return new DatosCompra(
                base.proveedorRuc(), base.proveedorRazonSocial(), base.proveedorDireccion(),
                base.tipoDocumento(), base.numeroComprobante(), base.claveAcceso(),
                base.fechaEmision(), fechaAut,
                base.baseIva15(), base.baseIva0(), base.baseNoObjeto(), base.baseExento(),
                base.valorIva15(), base.importeTotal(), base.moneda());
        }

        return extraerDeRaizComprobante(root);
    }

    /**
     * SRI emite {@code fechaAutorizacion} como ISO con offset o sin. Aceptar ambas variantes
     * y tolerar fracciones de segundo.
     */
    private static Instant parsearFechaAutorizacion(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        // Intentos en orden de probabilidad
        try { return ZonedDateTime.parse(t).toInstant(); } catch (DateTimeParseException ignored) {}
        try { return java.time.OffsetDateTime.parse(t).toInstant(); } catch (DateTimeParseException ignored) {}
        try {
            // Sin offset (asumimos zona Ecuador -05:00 si el SRI omitió)
            return java.time.LocalDateTime.parse(t).toInstant(ZoneOffset.of("-05:00"));
        } catch (DateTimeParseException ignored) {}
        return null;
    }

    private DatosCompra extraerDeRaizComprobante(Element root) {
        String nombreRaiz = root.getLocalName() != null ? root.getLocalName() : root.getNodeName();

        String tipoDoc = switch (nombreRaiz) {
            case "factura" -> "FACTURA";
            case "notaCredito" -> "NOTA_CREDITO";
            case "notaDebito" -> "NOTA_DEBITO";
            case "liquidacionCompra" -> "LIQUIDACION_COMPRA";
            default -> throw new XmlParserException(
                "Elemento raíz no soportado: <" + nombreRaiz + ">. Esperado: factura, notaCredito, notaDebito o liquidacionCompra.");
        };

        Element infoTributaria = primerElemento(root, "infoTributaria")
            .orElseThrow(() -> new XmlParserException("Falta <infoTributaria>"));

        String ruc = req(infoTributaria, "ruc");
        String razonSocial = req(infoTributaria, "razonSocial");
        String estab = req(infoTributaria, "estab");
        String ptoEmi = req(infoTributaria, "ptoEmi");
        String secuencial = req(infoTributaria, "secuencial");
        String claveAcceso = req(infoTributaria, "claveAcceso");
        if (claveAcceso.length() != 49 || !claveAcceso.matches("[0-9]{49}")) {
            throw new XmlParserException("claveAcceso inválida: debe tener exactamente 49 dígitos");
        }
        String numeroComp = estab + "-" + ptoEmi + "-" + secuencial;

        // infoFactura / infoNotaCredito / etc. — buscar el bloque de info según tipo
        String tagInfo = switch (tipoDoc) {
            case "FACTURA" -> "infoFactura";
            case "NOTA_CREDITO" -> "infoNotaCredito";
            case "NOTA_DEBITO" -> "infoNotaDebito";
            case "LIQUIDACION_COMPRA" -> "infoLiquidacionCompra";
            default -> throw new XmlParserException("Tipo de documento no soportado: " + tipoDoc);
        };
        Element info = primerElemento(root, tagInfo)
            .orElseThrow(() -> new XmlParserException("Falta <" + tagInfo + ">"));

        LocalDate fechaEmision = parsearFecha(req(info, "fechaEmision"));
        String direccion = textoOpcional(info, "dirMatriz")
            .orElseGet(() -> textoOpcional(info, "dirEstablecimiento").orElse(null));
        BigDecimal importeTotal = parsearMoney(req(info, "importeTotal"));
        String moneda = textoOpcional(info, "moneda").orElse("DOLAR");

        // Bases por tarifa: están dentro de <totalConImpuestos><totalImpuesto>...
        BigDecimal baseIva15 = BigDecimal.ZERO;
        BigDecimal baseIva0 = BigDecimal.ZERO;
        BigDecimal baseNoObjeto = BigDecimal.ZERO;
        BigDecimal baseExento = BigDecimal.ZERO;
        BigDecimal valorIva15 = BigDecimal.ZERO;

        Element totales = primerElemento(info, "totalConImpuestos").orElse(null);
        if (totales != null) {
            NodeList nodos = totales.getElementsByTagName("totalImpuesto");
            // Fallback para XMLs con namespace (mismo patrón que primerElemento)
            if (nodos.getLength() == 0) {
                nodos = totales.getElementsByTagNameNS("*", "totalImpuesto");
            }
            for (int i = 0; i < nodos.getLength(); i++) {
                Element t = (Element) nodos.item(i);
                String codigo = textoOpcional(t, "codigo").orElse("2");
                String codigoPorc = textoOpcional(t, "codigoPorcentaje").orElse("0");
                BigDecimal baseImp = parsearMoney(textoOpcional(t, "baseImponible").orElse("0"));
                BigDecimal valor = parsearMoney(textoOpcional(t, "valor").orElse("0"));
                if (!"2".equals(codigo)) continue; // solo IVA — ICE/IRBPNR no se modelan acá
                switch (codigoPorc) {
                    case "0" -> baseIva0 = baseIva0.add(baseImp);
                    // 2=12% (histórico pre-2024), 3=14% (histórico 2017), 4=15%, 5=15% diferenciado, 8=15% turismo:
                    // todos se acumulan a "baseIva15" — la declaración 104 los junta. Si en el futuro
                    // necesitamos desglosar por tarifa exacta, dividir acá.
                    case "2", "3", "4", "5", "8" -> {
                        baseIva15 = baseIva15.add(baseImp);
                        valorIva15 = valorIva15.add(valor);
                    }
                    case "6" -> baseNoObjeto = baseNoObjeto.add(baseImp);
                    case "7" -> baseExento = baseExento.add(baseImp);
                    default -> log.warn("codigoPorcentaje IVA desconocido en XML: '{}' (base={}, valor={})",
                        codigoPorc, baseImp, valor);
                }
            }
        }

        return new DatosCompra(
            ruc, razonSocial, direccion, tipoDoc, numeroComp, claveAcceso, fechaEmision,
            null,  // fechaAutorizacion solo viene del envelope <autorizacion>, no del comprobante pelado
            baseIva15, baseIva0, baseNoObjeto, baseExento, valorIva15,
            importeTotal, moneda);
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private Document parsearXml(byte[] xml) {
        try {
            return secureBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new XmlParserException("XML mal formado: " + e.getMessage(), e);
        }
    }

    private Document parsearXml(String xmlString) {
        try {
            return secureBuilder().parse(new InputSource(new StringReader(xmlString)));
        } catch (Exception e) {
            throw new XmlParserException("XML interno mal formado: " + e.getMessage(), e);
        }
    }

    private static DocumentBuilder secureBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        // XXE-safe: deshabilitar DTD + external entities (crítico, input no confiable)
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setExpandEntityReferences(false);
        f.setNamespaceAware(true);
        return f.newDocumentBuilder();
    }

    /**
     * Busca el primer elemento hijo con el {@code tag} dado, prefiriendo descendientes
     * directos. Maneja XMLs con namespaces (ej. {@code <ns:factura xmlns:ns="...">}) usando
     * {@link Element#getElementsByTagNameNS} con wildcard. Esto cubre el caso donde algún
     * proveedor SRI emite XML con namespace prefixed, donde el lookup local-name falla.
     */
    private static java.util.Optional<Element> primerElemento(Element padre, String tag) {
        // 1) lookup directo por nombre completo (sin namespace, default JAXP)
        NodeList nl = padre.getElementsByTagName(tag);
        // 2) si no encontró nada, probar por local-name con cualquier namespace (XML namespaced)
        if (nl.getLength() == 0) {
            nl = padre.getElementsByTagNameNS("*", tag);
        }
        if (nl.getLength() == 0) return java.util.Optional.empty();
        // Preferir descendientes directos (evita matches falsos en elementos anidados)
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getParentNode() == padre) return java.util.Optional.of((Element) n);
        }
        // Sin directo: devolver el primero (caso namespace anidado)
        return java.util.Optional.of((Element) nl.item(0));
    }

    private static String req(Element padre, String tag) {
        return textoOpcional(padre, tag)
            .orElseThrow(() -> new XmlParserException("Falta o vacío: <" + tag + ">"));
    }

    private static java.util.Optional<String> textoOpcional(Element padre, String tag) {
        String t = textoDirecto(padre, tag);
        return (t == null || t.isBlank())
            ? java.util.Optional.empty()
            : java.util.Optional.of(t.trim());
    }

    private static String textoDirecto(Element padre, String tag) {
        return primerElemento(padre, tag).map(Element::getTextContent).orElse(null);
    }

    private static LocalDate parsearFecha(String s) {
        try {
            return LocalDate.parse(s, FECHA_SRI);
        } catch (DateTimeParseException e) {
            throw new XmlParserException("Fecha SRI inválida (esperado dd/MM/yyyy): " + s);
        }
    }

    private static BigDecimal parsearMoney(String s) {
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            throw new XmlParserException("Número decimal inválido: " + s);
        }
    }

    public static class XmlParserException extends RuntimeException {
        public XmlParserException(String m) { super(m); }
        public XmlParserException(String m, Throwable c) { super(m, c); }
    }
}
