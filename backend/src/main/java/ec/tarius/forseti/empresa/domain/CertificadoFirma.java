package ec.tarius.forseti.empresa.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Custodia del .p12 + su contraseña — ADR-6, RL-6.
 *
 * Los campos p12Cifrado y passwordCifrada son BLOBS opacos (AES-256-GCM aplicado en la app).
 * NUNCA serializar al frontend (@JsonIgnore). NUNCA loguear.
 * Solo el servicio firmador descifra al momento de firmar.
 */
@Entity
@Table(name = "certificado_firma")
public class CertificadoFirma {

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    // Sin @Lob: en Postgres @Lob mapea a OID (large objects legacy);
    // queremos BYTEA inline. columnDefinition fuerza el tipo correcto.
    @JsonIgnore
    @Column(name = "p12_cifrado", nullable = false, columnDefinition = "bytea")
    private byte[] p12Cifrado;

    @JsonIgnore
    @Column(name = "password_cifrada", nullable = false, columnDefinition = "bytea")
    private byte[] passwordCifrada;

    @Column(name = "sujeto_cn", length = 300)
    private String sujetoCn;

    @Column(name = "emisor_cn", length = 300)
    private String emisorCn;

    @Column(name = "numero_serie", length = 100)
    private String numeroSerie;

    @Column(name = "vigente_desde")
    private Instant vigenteDesde;

    @Column(name = "vigente_hasta", nullable = false)
    private Instant vigenteHasta;

    @Column(name = "activo", nullable = false)
    private boolean activo = true;

    @Column(name = "cargado_por_usuario_id")
    private UUID cargadoPorUsuarioId;

    @Column(name = "cargado_at", nullable = false, updatable = false)
    private Instant cargadoAt = Instant.now();

    protected CertificadoFirma() {}

    public static CertificadoFirma nuevo(UUID empresaId, byte[] p12Cifrado, byte[] passwordCifrada,
                                          String sujetoCn, String emisorCn, String numeroSerie,
                                          Instant vigenteDesde, Instant vigenteHasta,
                                          UUID cargadoPor) {
        CertificadoFirma c = new CertificadoFirma();
        c.empresaId = empresaId;
        c.p12Cifrado = p12Cifrado;
        c.passwordCifrada = passwordCifrada;
        c.sujetoCn = sujetoCn;
        c.emisorCn = emisorCn;
        c.numeroSerie = numeroSerie;
        c.vigenteDesde = vigenteDesde;
        c.vigenteHasta = vigenteHasta;
        c.cargadoPorUsuarioId = cargadoPor;
        return c;
    }

    public void desactivar() { this.activo = false; }
    public void activar()    { this.activo = true; }

    public UUID getId() { return id; }
    public UUID getEmpresaId() { return empresaId; }

    /** Solo el servicio firmador llama a esto. Nunca exponer. */
    @JsonIgnore
    public byte[] getP12Cifrado() { return p12Cifrado; }

    @JsonIgnore
    public byte[] getPasswordCifrada() { return passwordCifrada; }

    public String getSujetoCn() { return sujetoCn; }
    public String getEmisorCn() { return emisorCn; }
    public String getNumeroSerie() { return numeroSerie; }
    public Instant getVigenteDesde() { return vigenteDesde; }
    public Instant getVigenteHasta() { return vigenteHasta; }
    public boolean isActivo() { return activo; }
    public UUID getCargadoPorUsuarioId() { return cargadoPorUsuarioId; }
    public Instant getCargadoAt() { return cargadoAt; }

    /** No expone el blob — solo metadata segura. */
    @Override
    public String toString() {
        return "CertificadoFirma{id=" + id + ", empresaId=" + empresaId
            + ", sujetoCn='" + sujetoCn + "', vigenteHasta=" + vigenteHasta
            + ", activo=" + activo + "}";
    }
}
