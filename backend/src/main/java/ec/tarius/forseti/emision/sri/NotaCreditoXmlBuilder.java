package ec.tarius.forseti.emision.sri;

import ec.tarius.forseti.emision.domain.Comprobante;
import ec.tarius.forseti.emision.domain.ComprobanteDetalle;
import ec.tarius.forseti.empresa.domain.Empresa;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static ec.tarius.forseti.emision.sri.FacturaXmlBuilder.money;
import static ec.tarius.forseti.emision.sri.FacturaXmlBuilder.qty;

/**
 * Constructor del XML de Nota de Crédito SRI v1.1.0 (codDoc=04).
 *
 * Estructura paralela a {@link FacturaXmlBuilder} pero con:
 *   - root {@code <notaCredito>} (NO {@code <factura>}).
 *   - codDoc "04".
 *   - bloque {@code <infoNotaCredito>} (sustituye a {@code <infoFactura>}) que incluye:
 *     {@code codDocModificado}, {@code numDocModificado}, {@code fechaEmisionDocSustento}, {@code motivo}.
 *   - {@code <detalles>} con {@code <detalle>} pero sin {@code <pagos>} (la NC no se cobra,
 *     se aplica al saldo).
 *
 * IMPORTANTE: la firma XAdES-BES se aplica DESPUÉS por XadesSigner; no se incluye acá.
 */
public final class NotaCreditoXmlBuilder {

    public static final String VERSION = "1.1.0";
    public static final String COD_DOC = "04";
    private static final DateTimeFormatter FECHA_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private NotaCreditoXmlBuilder() {}

    public static Document construir(Empresa emisor, Comprobante nc,
                                      List<ComprobanteDetalle> detalles) {
        Document doc = nuevoDocumento();
        Element root = doc.createElement("notaCredito");
        root.setAttribute("id", "comprobante");
        root.setAttribute("version", VERSION);
        doc.appendChild(root);

        root.appendChild(infoTributaria(doc, emisor, nc));
        root.appendChild(infoNotaCredito(doc, emisor, nc));
        root.appendChild(detalles(doc, detalles));
        root.appendChild(infoAdicional(doc, nc));
        return doc;
    }

    private static Element infoTributaria(Document doc, Empresa emisor, Comprobante nc) {
        Element it = doc.createElement("infoTributaria");
        agregar(it, "ambiente", "PRUEBAS".equals(nc.getAmbiente()) ? "1" : "2");
        agregar(it, "tipoEmision", "1");
        agregar(it, "razonSocial", emisor.getRazonSocial());
        if (notBlank(emisor.getNombreComercial())) {
            agregar(it, "nombreComercial", emisor.getNombreComercial());
        }
        agregar(it, "ruc", emisor.getRuc());
        agregar(it, "claveAcceso", nc.getClaveAcceso());
        agregar(it, "codDoc", COD_DOC);
        String[] partes = nc.getNumeroComprobante().split("-");
        agregar(it, "estab", partes[0]);
        agregar(it, "ptoEmi", partes[1]);
        agregar(it, "secuencial", partes[2]);
        agregar(it, "dirMatriz", emisor.getDireccion() != null ? emisor.getDireccion() : "S/N");
        String leyendaRimpe = leyendaRimpe(emisor.getRegimenTributario());
        if (leyendaRimpe != null) agregar(it, "contribuyenteRimpe", leyendaRimpe);
        return it;
    }

    private static Element infoNotaCredito(Document doc, Empresa emisor, Comprobante nc) {
        Element inc = doc.createElement("infoNotaCredito");
        agregar(inc, "fechaEmision", nc.getFechaEmision().format(FECHA_FMT));
        if (notBlank(emisor.getDireccion())) {
            agregar(inc, "dirEstablecimiento", emisor.getDireccion());
        }
        agregar(inc, "tipoIdentificacionComprador", nc.getReceptorTipoId());
        agregar(inc, "razonSocialComprador", nc.getReceptorRazonSocial());
        agregar(inc, "identificacionComprador", nc.getReceptorIdentificacion());
        if (notBlank(nc.getReceptorDireccion())) {
            agregar(inc, "direccionComprador", nc.getReceptorDireccion());
        }
        // contribuyenteEspecial es OPCIONAL en el XSD SRI. Solo se incluye si la empresa
        // está designada como tal (campo en empresa). Para el 99% de empresas se omite.
        if (notBlank(emisor.getCodigoContribuyenteEspecial())) {
            agregar(inc, "contribuyenteEspecial", emisor.getCodigoContribuyenteEspecial());
        }
        agregar(inc, "obligadoContabilidad", emisor.isObligadoContabilidad() ? "SI" : "NO");

        // Doc modificado — campos OBLIGATORIOS en NC v1.1.0
        agregar(inc, "codDocModificado", nc.getDocModificadoTipo() != null
            ? nc.getDocModificadoTipo() : "01");
        agregar(inc, "numDocModificado", nc.getDocModificadoNumero() != null
            ? nc.getDocModificadoNumero() : nc.getNumeroComprobante());
        agregar(inc, "fechaEmisionDocSustento",
            nc.getDocModificadoFecha() != null
                ? nc.getDocModificadoFecha().format(FECHA_FMT)
                : nc.getFechaEmision().format(FECHA_FMT));

        agregar(inc, "totalSinImpuestos", money(nc.getSubtotalSinImpuestos()));
        agregar(inc, "valorModificacion", money(nc.getImporteTotal()));
        agregar(inc, "moneda", nc.getMoneda());

        Element totalConImp = doc.createElement("totalConImpuestos");
        Element ti = doc.createElement("totalImpuesto");
        agregar(ti, "codigo", "2");
        BigDecimal tarifa = nc.getTotalIva().signum() > 0
            ? new BigDecimal("15.00") : new BigDecimal("0.00");
        agregar(ti, "codigoPorcentaje", tarifa.signum() > 0 ? "4" : "0");
        agregar(ti, "baseImponible", money(nc.getSubtotalSinImpuestos()));
        agregar(ti, "valor", money(nc.getTotalIva()));
        totalConImp.appendChild(ti);
        inc.appendChild(totalConImp);

        agregar(inc, "motivo", nc.getMotivo() != null ? nc.getMotivo() : "Devolución");
        return inc;
    }

    private static Element detalles(Document doc, List<ComprobanteDetalle> lineas) {
        Element det = doc.createElement("detalles");
        for (ComprobanteDetalle d : lineas) {
            Element l = doc.createElement("detalle");
            agregar(l, "codigoInterno", d.getCodigoPrincipal());
            if (notBlank(d.getCodigoAuxiliar())) {
                agregar(l, "codigoAdicional", d.getCodigoAuxiliar());
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

    private static Element infoAdicional(Document doc, Comprobante nc) {
        Element ia = doc.createElement("infoAdicional");
        if (notBlank(nc.getReceptorEmail())) {
            Element campo = doc.createElement("campoAdicional");
            campo.setAttribute("nombre", "email");
            campo.setTextContent(nc.getReceptorEmail());
            ia.appendChild(campo);
        }
        if (!ia.hasChildNodes()) {
            Element campo = doc.createElement("campoAdicional");
            campo.setAttribute("nombre", "observaciones");
            campo.setTextContent("Nota de crédito generada por Forseti");
            ia.appendChild(campo);
        }
        return ia;
    }

    // ──────── helpers ────────

    private static Document nuevoDocumento() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder b = f.newDocumentBuilder();
            Document d = b.newDocument();
            d.setXmlStandalone(true);
            return d;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("DocumentBuilder", e);
        }
    }

    private static void agregar(Element padre, String tag, String texto) {
        Element e = padre.getOwnerDocument().createElement(tag);
        e.setTextContent(texto);
        padre.appendChild(e);
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static String leyendaRimpe(String regimen) {
        if (regimen == null) return null;
        return switch (regimen) {
            case "RIMPE_EMPRENDEDOR" -> "CONTRIBUYENTE RÉGIMEN RIMPE";
            case "RIMPE_NP"          -> "CONTRIBUYENTE NEGOCIO POPULAR — RÉGIMEN RIMPE";
            default -> null;
        };
    }
}
