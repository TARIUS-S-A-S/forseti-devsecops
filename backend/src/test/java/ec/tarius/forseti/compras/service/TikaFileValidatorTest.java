package ec.tarius.forseti.compras.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sprint 5 gate ④: Tika debe rechazar archivos con extensión falsa.
 */
class TikaFileValidatorTest {

    private final TikaFileValidator validator = new TikaFileValidator();

    // PDF mínimo válido (header + EOF)
    private static final byte[] PDF_REAL = """
        %PDF-1.4
        1 0 obj <</Type/Catalog>> endobj
        trailer <</Root 1 0 R>>
        %%EOF
        """.getBytes(StandardCharsets.US_ASCII);

    // XML SRI mínimo
    private static final byte[] XML_REAL = """
        <?xml version="1.0" encoding="UTF-8"?>
        <factura id="comprobante" version="2.1.0">
          <infoTributaria><razonSocial>X</razonSocial></infoTributaria>
        </factura>
        """.getBytes(StandardCharsets.UTF_8);

    // Bytes binarios al azar — Tika los detectará como octet-stream
    private static final byte[] BINARIO_RANDOM = new byte[]{
        (byte)0x4D, (byte)0x5A, 0x00, 0x01, 0x02, 0x03,  // MZ = PE header (.exe)
        0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A
    };

    @Test
    void pdf_real_con_extension_pdf_pasa() {
        String mime = validator.validar(PDF_REAL, "factura.pdf");
        assertThat(mime).isEqualTo("application/pdf");
    }

    @Test
    void xml_real_con_extension_xml_pasa() {
        String mime = validator.validar(XML_REAL, "factura.xml");
        assertThat(mime).isEqualTo("application/xml");
    }

    @Test
    void exe_renombrado_a_pdf_es_rechazado_gate_4() {
        assertThatThrownBy(() -> validator.validar(BINARIO_RANDOM, "factura.pdf"))
            .isInstanceOf(TikaFileValidator.UnsupportedAttachmentException.class)
            .hasMessageContaining("no permitido");
    }

    @Test
    void xml_renombrado_a_pdf_es_rechazado_por_cross_check() {
        // El contenido es XML pero la extensión dice PDF → Tika detecta XML,
        // pero el cross-check con extensión lo rechaza
        assertThatThrownBy(() -> validator.validar(XML_REAL, "factura.pdf"))
            .isInstanceOf(TikaFileValidator.UnsupportedAttachmentException.class)
            .hasMessageContaining("Inconsistencia");
    }

    @Test
    void archivo_vacio_es_rechazado() {
        assertThatThrownBy(() -> validator.validar(new byte[0], "x.pdf"))
            .isInstanceOf(TikaFileValidator.UnsupportedAttachmentException.class)
            .hasMessageContaining("vacío");
    }

    @Test
    void archivo_demasiado_grande_es_rechazado() {
        byte[] enorme = new byte[11 * 1024 * 1024]; // 11 MB > límite 10 MB
        assertThatThrownBy(() -> validator.validar(enorme, "grande.pdf"))
            .isInstanceOf(TikaFileValidator.UnsupportedAttachmentException.class)
            .hasMessageContaining("10 MB");
    }
}
