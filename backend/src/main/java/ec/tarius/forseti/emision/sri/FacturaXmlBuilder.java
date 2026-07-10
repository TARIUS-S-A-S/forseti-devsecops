package ec.tarius.forseti.emision.sri;

import ec.tarius.forseti.emision.domain.Comprobante;
import ec.tarius.forseti.emision.domain.ComprobanteDetalle;
import ec.tarius.forseti.empresa.domain.Empresa;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.XMLConstants;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Construye el XML de una factura SRI versión 2.1.0 conforme a la ficha técnica
 * de comprobantes electrónicos vigente.
 *
 * Reglas duras:
 *   - El ORDEN de los elementos importa (XSD usa xs:sequence). No reordenar.
 *   - Decimales: 2 dígitos en totales/impuestos, 6 en cantidad/precioUnitario.
 *   - Separador decimal "." (no ",").
 *   - El root <factura> lleva id="comprobante" y version="2.1.0" — xades4j lo firma
 *     enveloped referenciando ese id.
 *   - El XML se serializa en UTF-8 sin declaración stand-alone.
 *
 * NO incluye la firma — eso lo hace XadesSigner sobre el Document devuelto.
 */
public final class FacturaXmlBuilder {

    public static final String VERSION = "2.1.0";
    private static final DateTimeFormatter FECHA_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private FacturaXmlBuilder() {}

    /**
     * Construye el Document XML de la factura. NO firma ni serializa — solo arma el DOM.
     */
    public static Document construir(Empresa emisor, Comprobante factura,
                                      List<ComprobanteDetalle> detalles) {
        Document doc = nuevoDocumento();

        Element root = doc.createElement("factura");
        root.setAttribute("id", "comprobante");
        root.setAttribute("version", VERSION);
        doc.appendChild(root);

        root.appendChild(infoTributaria(doc, emisor, factura));
        root.appendChild(infoFactura(doc, emisor, factura));
        root.appendChild(detalles(doc, detalles));
        root.appendChild(infoAdicional(doc, factura));

        return doc;
    }

    /**
     * Serializa el Document como bytes UTF-8 (sin re-pretty-printing — la firma posterior
     * cuenta caracteres exactos del XML canonical).
     */
    public static byte[] serializar(Document doc) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.INDENT, "no");
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo serializar el XML factura", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // <infoTributaria>
    // ─────────────────────────────────────────────────────────────────────────
    private static Element infoTributaria(Document doc, Empresa emisor, Comprobante f) {
        Element it = doc.createElement("infoTributaria");
        agregar(it, "ambiente", "PRUEBAS".equals(f.getAmbiente()) ? "1" : "2");
        agregar(it, "tipoEmision", "1");  // NORMAL — único válido en el offline post-2024
        agregar(it, "razonSocial", emisor.getRazonSocial());
        if (notBlank(emisor.getNombreComercial())) {
            agregar(it, "nombreComercial", emisor.getNombreComercial());
        }
        agregar(it, "ruc", emisor.getRuc());
        agregar(it, "claveAcceso", f.getClaveAcceso());
        agregar(it, "codDoc", "01");                                  // factura
        // numero_comprobante "001-001-000000001"
        String[] partes = f.getNumeroComprobante().split("-");
        agregar(it, "estab", partes[0]);
        agregar(it, "ptoEmi", partes[1]);
        agregar(it, "secuencial", partes[2]);
        agregar(it, "dirMatriz", emisor.getDireccion() != null ? emisor.getDireccion() : "S/N");
        if (emisor.isAgenteRetencion()) {
            // "1" para los designados en la última resolución; el detalle del nº de resolución
            // se completa en Sprint 7 cuando se configure por empresa
            agregar(it, "agenteRetencion", "1");
        }
        String leyendaRimpe = leyendaRimpe(emisor.getRegimenTributario());
        if (leyendaRimpe != null) {
            agregar(it, "contribuyenteRimpe", leyendaRimpe);
        }
        return it;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // <infoFactura>
    // ─────────────────────────────────────────────────────────────────────────
    private static Element infoFactura(Document doc, Empresa emisor, Comprobante f) {
        Element inf = doc.createElement("infoFactura");
        agregar(inf, "fechaEmision", f.getFechaEmision().format(FECHA_FMT));
        if (notBlank(emisor.getDireccion())) {
            agregar(inf, "dirEstablecimiento", emisor.getDireccion());
        }
        agregar(inf, "obligadoContabilidad", emisor.isObligadoContabilidad() ? "SI" : "NO");
        agregar(inf, "tipoIdentificacionComprador", f.getReceptorTipoId());
        agregar(inf, "razonSocialComprador", f.getReceptorRazonSocial());
        agregar(inf, "identificacionComprador", f.getReceptorIdentificacion());
        if (notBlank(f.getReceptorDireccion())) {
            agregar(inf, "direccionComprador", f.getReceptorDireccion());
        }
        agregar(inf, "totalSinImpuestos", money(f.getSubtotalSinImpuestos()));
        agregar(inf, "totalDescuento", money(f.getTotalDescuento()));

        // totalConImpuestos — Sprint 3 fase A asume 1 tarifa de IVA por factura (el caso 99%).
        // Fase B agrupa por (codigo, codigoPorcentaje) desde los detalles cuando aparezca el caso multi-tarifa.
        Element totalConImp = doc.createElement("totalConImpuestos");
        Element ti = doc.createElement("totalImpuesto");
        agregar(ti, "codigo", "2");
        BigDecimal tarifa = f.getTotalIva().signum() > 0
            ? new BigDecimal("15.00") : new BigDecimal("0.00");
        agregar(ti, "codigoPorcentaje", tarifa.signum() > 0 ? "4" : "0");
        agregar(ti, "baseImponible", money(f.getSubtotalSinImpuestos()));
        agregar(ti, "tarifa", tarifa.setScale(2, RoundingMode.HALF_UP).toPlainString());
        agregar(ti, "valor", money(f.getTotalIva()));
        totalConImp.appendChild(ti);
        inf.appendChild(totalConImp);

        agregar(inf, "propina", "0.00");
        agregar(inf, "importeTotal", money(f.getImporteTotal()));
        agregar(inf, "moneda", f.getMoneda());

        Element pagos = doc.createElement("pagos");
        Element pago = doc.createElement("pago");
        agregar(pago, "formaPago", f.getFormaPago());
        agregar(pago, "total", money(f.getImporteTotal()));
        if (f.getPlazoDias() > 0) {
            agregar(pago, "plazo", String.valueOf(f.getPlazoDias()));
            agregar(pago, "unidadTiempo", "dias");
        }
        pagos.appendChild(pago);
        inf.appendChild(pagos);

        return inf;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // <detalles>
    // ─────────────────────────────────────────────────────────────────────────
    private static Element detalles(Document doc, List<ComprobanteDetalle> lineas) {
        Element det = doc.createElement("detalles");
        for (ComprobanteDetalle d : lineas) {
            Element l = doc.createElement("detalle");
            agregar(l, "codigoPrincipal", d.getCodigoPrincipal());
            if (notBlank(d.getCodigoAuxiliar())) {
                agregar(l, "codigoAuxiliar", d.getCodigoAuxiliar());
            }
            agregar(l, "descripcion", d.getDescripcion());
            agregar(l, "cantidad", qty(d.getCantidad()));
            agregar(l, "precioUnitario", qty(d.getPrecioUnitario()));
            agregar(l, "descuento", money(d.getDescuento()));
            agregar(l, "precioTotalSinImpuesto", money(d.getPrecioTotalSinImpuesto()));

            Element impuestos = doc.createElement("impuestos");
            Element imp = doc.createElement("impuesto");
            agregar(imp, "codigo", d.getCodigoImpuesto());
            agregar(imp, "codigoPorcentaje", d.getCodigoPorcentaje());
            agregar(imp, "tarifa", d.getTarifa().setScale(2, RoundingMode.HALF_UP).toPlainString());
            agregar(imp, "baseImponible", money(d.getBaseImponible()));
            agregar(imp, "valor", money(d.getValorImpuesto()));
            impuestos.appendChild(imp);
            l.appendChild(impuestos);

            det.appendChild(l);
        }
        return det;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // <infoAdicional>
    // ─────────────────────────────────────────────────────────────────────────
    private static Element infoAdicional(Document doc, Comprobante f) {
        Element ia = doc.createElement("infoAdicional");
        // Al menos un campoAdicional para que el XSD pase (si infoAdicional está presente).
        // Usamos el email del receptor si existe; si no, una nota técnica.
        if (notBlank(f.getReceptorEmail())) {
            Element campo = doc.createElement("campoAdicional");
            campo.setAttribute("nombre", "email");
            campo.setTextContent(f.getReceptorEmail());
            ia.appendChild(campo);
        }
        if (notBlank(f.getReceptorTelefono())) {
            Element campo = doc.createElement("campoAdicional");
            campo.setAttribute("nombre", "telefono");
            campo.setTextContent(f.getReceptorTelefono());
            ia.appendChild(campo);
        }
        // Si no hay ninguno, agregar uno genérico para cumplir minOccurs
        if (!ia.hasChildNodes()) {
            Element campo = doc.createElement("campoAdicional");
            campo.setAttribute("nombre", "observaciones");
            campo.setTextContent("Generado por Forseti");
            ia.appendChild(campo);
        }
        return ia;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Formatea un BigDecimal con 2 decimales y punto (para totales e impuestos). */
    public static String money(BigDecimal v) {
        if (v == null) return "0.00";
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Formatea un BigDecimal con 6 decimales (para cantidad y precioUnitario). */
    public static String qty(BigDecimal v) {
        if (v == null) return "0.000000";
        return v.setScale(6, RoundingMode.HALF_UP).toPlainString();
    }

    private static void agregar(Element padre, String tag, String texto) {
        Element e = padre.getOwnerDocument().createElement(tag);
        e.setTextContent(texto);
        padre.appendChild(e);
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    /**
     * Mapea régimen → leyenda RIMPE exigida por el SRI en el XML factura.
     * El XSD oficial valida con patrón "CONTRIBUYENTE (NEGOCIO POPULAR - )?RÉGIMEN RIMPE"
     * — RÉGIMEN va con tilde. Si se quita, el SRI rechaza al validar el XSD.
     */
    static String leyendaRimpe(String regimen) {
        if (regimen == null) return null;
        return switch (regimen) {
            case "RIMPE_NP" -> "CONTRIBUYENTE NEGOCIO POPULAR - RÉGIMEN RIMPE";
            case "RIMPE_EMPRENDEDOR" -> "CONTRIBUYENTE RÉGIMEN RIMPE";
            default -> null;  // GENERAL no lleva leyenda
        };
    }

    private static Document nuevoDocumento() {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            // Defensa XXE (no estamos parseando, pero por las dudas)
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            return db.newDocument();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("No se pudo crear DocumentBuilder", e);
        }
    }

    /** Serializa a UTF-8 string para debug/tests. */
    public static String serializarString(Document doc) {
        return new String(serializar(doc), StandardCharsets.UTF_8);
    }
}
