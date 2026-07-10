package ec.tarius.forseti.compras.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Archivo adjunto a una compra (PDF, XML, etc.). HU-F3.
 *
 * Invariantes:
 *   - {@code mimeTypeReal} es el detectado por Apache Tika contra los bytes, NO la
 *     extensión del nombre (la extensión es manipulable). Si Tika reporta algo distinto
 *     a lo que la extensión sugiere, el upload se rechaza.
 *   - {@code sha256} permite detectar uploads duplicados a la misma compra.
 *   - Máximo 10 MB por archivo (validado en BD con CHECK + en código).
 */
@Entity
@Table(name = "compra_adjunto")
public class CompraAdjunto {

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Column(name = "compra_id", nullable = false)
    private UUID compraId;

    @Column(name = "nombre_original", nullable = false, length = 255)
    private String nombreOriginal;

    @Column(name = "mime_type_real", nullable = false, length = 80)
    private String mimeTypeReal;

    @Column(name = "tamano_bytes", nullable = false)
    private int tamanoBytes;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "contenido", nullable = false, columnDefinition = "bytea")
    private byte[] contenido;

    @Column(name = "creado_por_usuario_id")
    private UUID creadoPorUsuarioId;

    @Column(name = "creado_at", nullable = false, updatable = false)
    private Instant creadoAt = Instant.now();

    protected CompraAdjunto() {}

    public static CompraAdjunto nuevo(UUID empresaId, UUID compraId,
                                       String nombre, String mimeReal,
                                       byte[] contenido, String sha256,
                                       UUID creadoPor) {
        CompraAdjunto a = new CompraAdjunto();
        a.empresaId = empresaId;
        a.compraId = compraId;
        a.nombreOriginal = nombre;
        a.mimeTypeReal = mimeReal;
        a.contenido = contenido;
        a.tamanoBytes = contenido.length;
        a.sha256 = sha256;
        a.creadoPorUsuarioId = creadoPor;
        return a;
    }

    public UUID getId() { return id; }
    public UUID getEmpresaId() { return empresaId; }
    public UUID getCompraId() { return compraId; }
    public String getNombreOriginal() { return nombreOriginal; }
    public String getMimeTypeReal() { return mimeTypeReal; }
    public int getTamanoBytes() { return tamanoBytes; }
    public String getSha256() { return sha256; }
    public byte[] getContenido() { return contenido; }
    public UUID getCreadoPorUsuarioId() { return creadoPorUsuarioId; }
    public Instant getCreadoAt() { return creadoAt; }
}
