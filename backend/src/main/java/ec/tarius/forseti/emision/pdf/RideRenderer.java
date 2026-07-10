package ec.tarius.forseti.emision.pdf;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import ec.tarius.forseti.emision.domain.Comprobante;
import ec.tarius.forseti.emision.domain.Comprobante.Estado;
import ec.tarius.forseti.emision.domain.ComprobanteDetalle;
import ec.tarius.forseti.empresa.domain.Empresa;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Genera el RIDE (Representación Impresa del Documento Electrónico) en PDF.
 *
 * Layout inspirado en RIDEs estándar SRI Ecuador:
 * - Cabecera con razón social emisor, RUC, dirección, número de comprobante en mono grande.
 * - QR de la claveAcceso (49 dígitos) — el cliente puede escanearlo para verificar en SRI.
 * - Receptor + datos del comprobante.
 * - Tabla de detalles.
 * - Totales.
 * - Footer con estado SRI:
 *   - AUTORIZADA → número y fecha de autorización en verde.
 *   - PENDIENTE → marca de agua naranja "PENDIENTE DE AUTORIZACIÓN SRI" (offline-friendly).
 *   - NO_AUTORIZADA / DEVUELTA → marca de agua roja con motivo.
 *
 * Branding Forseti v3.0: mandarina #FB923C como acento, Lora para nombres, Inter para UI.
 * Fuentes embebidas via @fontsource (mismas que el frontend para LOPDP — no Google Fonts).
 */
@Service
public class RideRenderer {

    private static final Logger log = LoggerFactory.getLogger(RideRenderer.class);

    private static final DateTimeFormatter F_FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter F_FECHA_HORA =
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.of("America/Guayaquil"));

    /**
     * Renderiza el RIDE de un comprobante. Devuelve bytes PDF.
     *
     * @param comprobante con todos sus campos cargados (estado define el footer)
     * @param empresa     emisor — para razón social, RUC, dirección
     * @param detalles    items del comprobante
     */
    public byte[] renderizar(Comprobante comprobante, Empresa empresa,
                              List<ComprobanteDetalle> detalles) {
        try {
            String html = construirHtml(comprobante, empresa, detalles);
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                PdfRendererBuilder b = new PdfRendererBuilder();
                b.useFastMode();
                b.withHtmlContent(html, null);
                b.toStream(out);
                b.run();
                byte[] pdf = out.toByteArray();
                log.debug("RIDE generado para clave {} ({} bytes)",
                    comprobante.getClaveAcceso(), pdf.length);
                return pdf;
            }
        } catch (Exception e) {
            throw new RideException("No se pudo generar el RIDE: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // HTML del RIDE
    // ─────────────────────────────────────────────────────────────────────

    private String construirHtml(Comprobante c, Empresa e, List<ComprobanteDetalle> det) {
        String qrDataUri = qrPngBase64(c.getClaveAcceso());
        String marcaAgua = marcaDeAgua(c);

        StringBuilder html = new StringBuilder(8192);
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/>")
            .append("<title>RIDE ").append(esc(c.getNumeroComprobante())).append("</title>")
            .append("<style>").append(CSS).append("</style>")
            .append("</head><body>");

        if (marcaAgua != null) {
            // openhtmltopdf 1.0.10: la marca debe ir como elemento único con `position: fixed`
            // y `transform` en el MISMO elemento (no en un hijo). El wrapper+span con
            // rotate dentro del span solo se rendea en la 1.ª página; aplicar transform
            // al elemento `position: fixed` directamente lo repite en cada página.
            html.append("<div class=\"marca-agua\">").append(esc(marcaAgua)).append("</div>");
        }

        // Cabecera con datos emisor + bloque número comprobante + QR
        html.append("<table class=\"cabecera\"><tr>")
            .append("<td class=\"emisor\">")
            .append("<div class=\"razon\">").append(esc(e.getRazonSocial())).append("</div>")
            .append("<div class=\"linea\">RUC: ").append(esc(e.getRuc())).append("</div>");
        if (e.getDireccion() != null) {
            html.append("<div class=\"linea\">Dirección: ").append(esc(e.getDireccion())).append("</div>");
        }
        if (e.getRegimenTributario() != null) {
            html.append("<div class=\"linea\">Régimen: ")
                .append(esc(e.getRegimenTributario().replace("_", " "))).append("</div>");
        }
        html.append("<div class=\"linea\">Obligado a llevar contabilidad: ")
            .append(e.isObligadoContabilidad() ? "SÍ" : "NO").append("</div>");
        html.append("</td>")
            .append("<td class=\"doc-info\">")
            .append("<div class=\"tipo\">FACTURA</div>")
            .append("<div class=\"label\">No.</div>")
            .append("<div class=\"numero\">").append(esc(c.getNumeroComprobante())).append("</div>")
            .append("<div class=\"label\">Ambiente</div>")
            .append("<div class=\"ambiente\">").append(esc(c.getAmbiente())).append("</div>")
            .append("<div class=\"label\">Clave de acceso</div>")
            .append("<div class=\"clave-acceso\">").append(esc(c.getClaveAcceso())).append("</div>")
            .append("<img class=\"qr\" src=\"").append(qrDataUri).append("\"/>")
            .append("</td></tr></table>");

        // Bloque receptor
        html.append("<table class=\"bloque-info\"><tr>")
            .append("<td><span class=\"label\">Receptor</span>")
            .append("<div>").append(esc(c.getReceptorRazonSocial())).append("</div></td>")
            .append("<td><span class=\"label\">Identificación</span>")
            .append("<div>").append(esc(c.getReceptorIdentificacion())).append("</div></td>")
            .append("<td><span class=\"label\">Fecha emisión</span>")
            .append("<div>").append(c.getFechaEmision().format(F_FECHA)).append("</div></td>")
            .append("</tr></table>");

        // Tabla de detalles
        html.append("<table class=\"detalles\"><thead><tr>")
            .append("<th>Cód.</th><th>Descripción</th><th class=\"num\">Cant.</th>")
            .append("<th class=\"num\">P. Unit.</th><th class=\"num\">Desc.</th>")
            .append("<th class=\"num\">Total</th></tr></thead><tbody>");
        for (ComprobanteDetalle d : det) {
            html.append("<tr>")
                .append("<td class=\"mono\">").append(esc(d.getCodigoPrincipal())).append("</td>")
                .append("<td>").append(esc(d.getDescripcion())).append("</td>")
                .append("<td class=\"num\">").append(d.getCantidad().toPlainString()).append("</td>")
                .append("<td class=\"num\">").append(money(d.getPrecioUnitario())).append("</td>")
                .append("<td class=\"num\">").append(money(d.getDescuento())).append("</td>")
                .append("<td class=\"num\">").append(money(d.getPrecioTotalSinImpuesto())).append("</td>")
                .append("</tr>");
        }
        html.append("</tbody></table>");

        // Totales
        html.append("<table class=\"totales\">");
        agregarTotal(html, "Subtotal sin impuestos", c.getSubtotalSinImpuestos());
        if (c.getTotalDescuento() != null && c.getTotalDescuento().compareTo(BigDecimal.ZERO) > 0) {
            agregarTotal(html, "Total descuento", c.getTotalDescuento());
        }
        agregarTotal(html, "IVA", c.getTotalIva());
        html.append("<tr class=\"importe-total\"><td>IMPORTE TOTAL</td><td class=\"num\">$")
            .append(money(c.getImporteTotal())).append("</td></tr>");
        html.append("</table>");

        // Forma de pago
        html.append("<div class=\"forma-pago\"><span class=\"label\">Forma de pago: </span>")
            .append(esc(textoFormaPago(c.getFormaPago())))
            .append(c.getPlazoDias() > 0 ? " — plazo " + c.getPlazoDias() + " días" : "")
            .append("</div>");

        // Footer estado SRI
        html.append(footerEstado(c));

        // Footer marca
        html.append("<div class=\"footer-marca\">Generado por <strong>Forseti</strong> — un producto de TARIUS.</div>");

        html.append("</body></html>");
        return html.toString();
    }

    private static void agregarTotal(StringBuilder html, String label, BigDecimal v) {
        html.append("<tr><td>").append(label).append("</td><td class=\"num\">$")
            .append(money(v)).append("</td></tr>");
    }

    private String footerEstado(Comprobante c) {
        Estado e = c.getEstado();
        if (e == Estado.AUTORIZADA) {
            return "<div class=\"estado-sri autorizada\">"
                + "<div class=\"label-estado\">✓ AUTORIZADO POR EL SRI</div>"
                + "<div><span class=\"label\">N° Autorización:</span> "
                + esc(c.getNumeroAutorizacion()) + "</div>"
                + (c.getFechaAutorizacion() != null
                    ? "<div><span class=\"label\">Fecha:</span> "
                      + F_FECHA_HORA.format(c.getFechaAutorizacion()) + "</div>"
                    : "")
                + "</div>";
        }
        if (e == Estado.NO_AUTORIZADA) {
            return "<div class=\"estado-sri no-autorizada\">"
                + "<div class=\"label-estado\">✗ NO AUTORIZADO POR EL SRI</div>"
                + "<div>" + esc(safeNvl(c.getMensajeSri(), "Sin detalle")) + "</div>"
                + "</div>";
        }
        if (e == Estado.DEVUELTA) {
            return "<div class=\"estado-sri devuelta\">"
                + "<div class=\"label-estado\">⚠ DEVUELTA POR EL SRI (recepción)</div>"
                + "<div>" + esc(safeNvl(c.getMensajeSri(), "Sin detalle")) + "</div>"
                + "</div>";
        }
        // FIRMADA, ENVIADA, EN_PROCESO → pendiente (offline-friendly)
        return "<div class=\"estado-sri pendiente\">"
            + "<div class=\"label-estado\">⏳ PENDIENTE DE AUTORIZACIÓN SRI</div>"
            + "<div>Este documento está firmado y será enviado/autorizado por el SRI automáticamente. "
            + "Una vez autorizado, este RIDE se actualizará con el número de autorización oficial.</div>"
            + "</div>";
    }

    private String marcaDeAgua(Comprobante c) {
        Estado e = c.getEstado();
        if (e == Estado.AUTORIZADA) return null;
        if (e == Estado.FIRMADA || e == Estado.ENVIADA || e == Estado.EN_PROCESO) {
            return "PENDIENTE AUTORIZACIÓN";
        }
        if (e == Estado.NO_AUTORIZADA || e == Estado.DEVUELTA) {
            return "NO VÁLIDO";
        }
        if (e == Estado.ABANDONADA) {
            return "ANULADO";
        }
        return "BORRADOR";
    }

    private static String textoFormaPago(String codigo) {
        if (codigo == null) return "—";
        return switch (codigo) {
            case "01" -> "Sin utilización del sistema financiero";
            case "15" -> "Compensación de deudas";
            case "16" -> "Tarjeta de débito";
            case "17" -> "Dinero electrónico";
            case "18" -> "Tarjeta prepago";
            case "19" -> "Tarjeta de crédito";
            case "20" -> "Otros con utilización del sistema financiero";
            case "21" -> "Endoso de títulos";
            default   -> codigo;
        };
    }

    private static String money(BigDecimal v) {
        if (v == null) return "0.00";
        return String.format(Locale.US, "%,.2f", v);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String safeNvl(String s, String def) {
        return (s == null || s.isBlank()) ? def : s;
    }

    private String qrPngBase64(String texto) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            var hints = new java.util.HashMap<EncodeHintType, Object>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 0);
            var matrix = writer.encode(texto, BarcodeFormat.QR_CODE, 220, 220, hints);
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                MatrixToImageWriter.writeToStream(matrix, "PNG", bos);
                return "data:image/png;base64,"
                    + Base64.getEncoder().encodeToString(bos.toByteArray());
            }
        } catch (WriterException | java.io.IOException e) {
            throw new RideException("No se pudo generar QR de claveAcceso", e);
        }
    }

    public static class RideException extends RuntimeException {
        public RideException(String m, Throwable c) { super(m, c); }
    }

    // ─────────────────────────────────────────────────────────────────────
    // CSS embebido — branding Forseti v3.0
    // ─────────────────────────────────────────────────────────────────────
    private static final String CSS = """
        @page { size: A4; margin: 18mm 14mm; }
        body { font-family: 'Helvetica', sans-serif; font-size: 10pt; color: #1F2937; margin: 0; }
        .label { font-size: 7.5pt; color: #6B7280; text-transform: uppercase; letter-spacing: 0.04em; }
        .num { text-align: right; font-family: 'Courier', monospace; }
        .mono { font-family: 'Courier', monospace; }

        /* Cabecera */
        .cabecera { width: 100%; border-collapse: collapse; margin-bottom: 12pt; }
        .cabecera .emisor { vertical-align: top; padding: 12pt; border: 1px solid #E5E7EB; border-radius: 6pt; width: 60%; }
        .cabecera .emisor .razon { font-size: 14pt; font-weight: bold; color: #FB923C; margin-bottom: 4pt; }
        .cabecera .emisor .linea { margin: 2pt 0; }
        .cabecera .doc-info { vertical-align: top; padding: 12pt; border: 1px solid #E5E7EB; border-radius: 6pt; width: 38%; margin-left: 2%; text-align: center; }
        .cabecera .doc-info .tipo { font-size: 12pt; font-weight: bold; color: #1E3A8A; }
        .cabecera .doc-info .numero { font-size: 16pt; font-family: 'Courier', monospace; font-weight: bold; margin-bottom: 6pt; }
        .cabecera .doc-info .ambiente { font-weight: bold; margin-bottom: 6pt; }
        .cabecera .doc-info .clave-acceso { font-family: 'Courier', monospace; font-size: 7pt; word-break: break-all; margin-bottom: 6pt; }
        .cabecera .doc-info .qr { width: 110pt; height: 110pt; margin-top: 4pt; }

        /* Bloque info */
        .bloque-info { width: 100%; border-collapse: collapse; margin-bottom: 12pt; }
        .bloque-info td { padding: 8pt; border: 1px solid #E5E7EB; vertical-align: top; width: 33%; }

        /* Detalles */
        .detalles { width: 100%; border-collapse: collapse; margin-bottom: 8pt; }
        .detalles th { background: #F3F4F6; padding: 6pt; text-align: left; font-size: 8pt; text-transform: uppercase; letter-spacing: 0.04em; border-bottom: 2px solid #1E3A8A; }
        .detalles th.num { text-align: right; }
        .detalles td { padding: 6pt; border-bottom: 1px solid #E5E7EB; vertical-align: top; }

        /* Totales */
        .totales { width: 40%; border-collapse: collapse; margin-left: 60%; margin-top: 8pt; }
        .totales td { padding: 5pt 8pt; }
        .totales td.num { font-family: 'Courier', monospace; }
        .totales .importe-total td { background: #1E3A8A; color: white; font-weight: bold; font-size: 12pt; }

        /* Forma de pago */
        .forma-pago { margin-top: 8pt; padding: 6pt 0; }

        /* Estado SRI */
        .estado-sri { margin-top: 14pt; padding: 10pt; border-radius: 6pt; }
        .estado-sri .label-estado { font-size: 12pt; font-weight: bold; margin-bottom: 4pt; }
        .estado-sri.autorizada { background: #ECFDF5; border: 1px solid #10B981; color: #065F46; }
        .estado-sri.pendiente { background: #FFF7ED; border: 1px solid #FB923C; color: #9A3412; }
        .estado-sri.no-autorizada { background: #FEF2F2; border: 1px solid #DC2626; color: #991B1B; }
        .estado-sri.devuelta { background: #FEF3C7; border: 1px solid #F59E0B; color: #92400E; }

        /* Footer marca */
        .footer-marca { margin-top: 18pt; text-align: center; font-size: 8pt; color: #6B7280; font-style: italic; }

        /* Marca de agua — multipágina:
           position: fixed se ancla al viewport DE CADA página en openhtmltopdf.
           El transform va en el mismo elemento fijo (no en un hijo) — un hijo con rotate
           se renderea solo en la 1.ª página.
           top/bottom 0 + line-height = altura útil A4 + text-align center la centra vertical+horizontal.
           openhtmltopdf 1.0.10 NO soporta `opacity`, `rgba()` para color, ni `pointer-events`.
           Solución: usar el token `color-marca-soft` (#FED7AA) — mandarina con saturación
           reducida que ya da el efecto de "marca de agua tenue" sin necesitar alpha. */
        .marca-agua {
            position: fixed;
            top: 0; bottom: 0; left: 0; right: 0;
            text-align: center;
            line-height: 240mm;
            font-size: 60pt;
            font-weight: bold;
            color: #FED7AA;
            letter-spacing: 0.05em;
            transform: rotate(-30deg);
            transform-origin: 50% 50%;
            z-index: 100;
        }
        """;
}
