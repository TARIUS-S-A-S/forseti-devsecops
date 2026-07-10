package ec.tarius.forseti.compras.service;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;

/**
 * Valida adjuntos con Apache Tika: detecta el MIME REAL desde los bytes (no desde
 * la extensión, que es manipulable). HU-F3 gate ④.
 *
 * Caso de uso: un atacante sube un .exe renombrado a "factura.pdf". La extensión dice PDF
 * pero los magic bytes son "MZ" (PE binary). Tika detecta el MIME real y bloqueamos.
 *
 * MIMEs permitidos: PDF y XML (los dos formatos reales de un comprobante recibido).
 * Cualquier otro tipo lanza {@link UnsupportedAttachmentException}.
 */
@Component
public class TikaFileValidator {

    /** MIMEs aceptados para adjuntos a una compra. */
    public static final Set<String> MIMES_PERMITIDOS = Set.of(
        "application/pdf",
        "application/xml",
        "text/xml"
    );

    public static final int MAX_BYTES = 10 * 1024 * 1024;  // 10 MB

    private final Tika tika = new Tika();

    /**
     * Detecta el MIME real del archivo y verifica que esté en la whitelist.
     *
     * @return el MIME real detectado por Tika.
     * @throws UnsupportedAttachmentException si el MIME real NO es PDF ni XML, o si la
     *         extensión declarada (vía {@code nombreOriginal}) no coincide con lo detectado,
     *         o si el archivo excede {@link #MAX_BYTES}.
     */
    public String validar(byte[] contenido, String nombreOriginal) {
        if (contenido == null || contenido.length == 0) {
            throw new UnsupportedAttachmentException("El archivo está vacío");
        }
        if (contenido.length > MAX_BYTES) {
            throw new UnsupportedAttachmentException(
                "El archivo excede 10 MB (tiene " + (contenido.length / 1024 / 1024) + " MB)");
        }

        String mimeReal;
        try {
            mimeReal = tika.detect(new ByteArrayInputStream(contenido), nombreOriginal);
        } catch (IOException e) {
            throw new UnsupportedAttachmentException(
                "No se pudo detectar el tipo de archivo: " + e.getMessage());
        }

        // Normalizar variantes de XML
        if (mimeReal != null) {
            mimeReal = mimeReal.toLowerCase();
            if (mimeReal.startsWith("application/xml") || mimeReal.startsWith("text/xml")) {
                mimeReal = "application/xml";
            }
        }

        if (mimeReal == null || !MIMES_PERMITIDOS.contains(mimeReal)) {
            throw new UnsupportedAttachmentException(
                "Tipo de archivo no permitido. Detectado: " + mimeReal
                + " (esperado: PDF o XML). El nombre declarado era: '" + nombreOriginal + "'");
        }

        // Cross-check con extensión declarada — si dice .pdf pero es XML, sospecha
        String nombreLower = nombreOriginal == null ? "" : nombreOriginal.toLowerCase();
        if (nombreLower.endsWith(".pdf") && !"application/pdf".equals(mimeReal)) {
            throw new UnsupportedAttachmentException(
                "Inconsistencia: el archivo se llama '" + nombreOriginal
                + "' pero su contenido real es " + mimeReal);
        }
        if ((nombreLower.endsWith(".xml")) && !"application/xml".equals(mimeReal)) {
            throw new UnsupportedAttachmentException(
                "Inconsistencia: el archivo se llama '" + nombreOriginal
                + "' pero su contenido real es " + mimeReal);
        }

        return mimeReal;
    }

    public static class UnsupportedAttachmentException extends RuntimeException {
        public UnsupportedAttachmentException(String m) { super(m); }
    }
}
