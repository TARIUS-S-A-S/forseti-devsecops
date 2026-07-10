package ec.tarius.forseti.auth.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sesion")
public class Sesion {

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "usuario_id", nullable = false)
    private UUID usuarioId;

    @Column(name = "empresa_activa_id")
    private UUID empresaActivaId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "ip")
    private String ip;

    @Column(name = "user_agent", columnDefinition = "text")
    private String userAgent;

    @Column(name = "creada_at", nullable = false, updatable = false)
    private Instant creadaAt = Instant.now();

    @Column(name = "ultima_actividad_at", nullable = false)
    private Instant ultimaActividadAt = Instant.now();

    @Column(name = "expira_at", nullable = false)
    private Instant expiraAt;

    @Column(name = "revocada_at")
    private Instant revocadaAt;

    protected Sesion() {}

    public static Sesion nueva(UUID usuarioId, UUID empresaActivaId, String tokenHash,
                                String ip, String userAgent, Instant expiraAt) {
        Sesion s = new Sesion();
        s.usuarioId = usuarioId;
        s.empresaActivaId = empresaActivaId;
        s.tokenHash = tokenHash;
        s.ip = ip;
        s.userAgent = userAgent;
        s.expiraAt = expiraAt;
        return s;
    }

    public boolean isActiva() {
        return revocadaAt == null && expiraAt.isAfter(Instant.now());
    }

    public void revocar() {
        this.revocadaAt = Instant.now();
    }

    public void marcarActividad() {
        this.ultimaActividadAt = Instant.now();
    }

    public void cambiarEmpresaActiva(UUID empresaId) {
        this.empresaActivaId = empresaId;
    }

    public UUID getId() { return id; }
    public UUID getUsuarioId() { return usuarioId; }
    public UUID getEmpresaActivaId() { return empresaActivaId; }
    public String getTokenHash() { return tokenHash; }
    public String getIp() { return ip; }
    public String getUserAgent() { return userAgent; }
    public Instant getCreadaAt() { return creadaAt; }
    public Instant getUltimaActividadAt() { return ultimaActividadAt; }
    public Instant getExpiraAt() { return expiraAt; }
    public Instant getRevocadaAt() { return revocadaAt; }
}
