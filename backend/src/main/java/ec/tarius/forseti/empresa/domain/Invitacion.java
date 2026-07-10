package ec.tarius.forseti.empresa.domain;

import ec.tarius.forseti.auth.domain.UsuarioEmpresa;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invitacion_empresa")
public class Invitacion {

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Column(name = "empresa_razon_social", nullable = false, length = 300)
    private String empresaRazonSocial;

    @Column(name = "email", nullable = false, columnDefinition = "citext")
    private String email;

    @Column(name = "nombre_invitado", length = 200)
    private String nombreInvitado;

    @Enumerated(EnumType.STRING)
    @Column(name = "rol", nullable = false, length = 20)
    private UsuarioEmpresa.Rol rol;

    /** Token URL-safe base64 de 256-bit. Solo viaja en el correo. */
    @Column(name = "token", nullable = false, unique = true)
    private String token;

    @Column(name = "invitada_por_usuario_id")
    private UUID invitadaPorUsuarioId;

    @Column(name = "creada_at", nullable = false, updatable = false)
    private Instant creadaAt = Instant.now();

    @Column(name = "expira_at", nullable = false)
    private Instant expiraAt;

    @Column(name = "aceptada_at")
    private Instant aceptadaAt;

    @Column(name = "aceptada_por_usuario_id")
    private UUID aceptadaPorUsuarioId;

    @Column(name = "cancelada_at")
    private Instant canceladaAt;

    @Column(name = "cancelada_por_usuario_id")
    private UUID canceladaPorUsuarioId;

    protected Invitacion() {}

    public static Invitacion nueva(UUID empresaId, String empresaRazonSocial,
                                    String email, String nombreInvitado,
                                    UsuarioEmpresa.Rol rol, String token,
                                    UUID invitadaPor, Instant expiraAt) {
        Invitacion i = new Invitacion();
        i.empresaId = empresaId;
        i.empresaRazonSocial = empresaRazonSocial;
        i.email = email.toLowerCase();
        i.nombreInvitado = nombreInvitado;
        i.rol = rol;
        i.token = token;
        i.invitadaPorUsuarioId = invitadaPor;
        i.expiraAt = expiraAt;
        return i;
    }

    public boolean estaPendiente() {
        return aceptadaAt == null && canceladaAt == null && expiraAt.isAfter(Instant.now());
    }

    public void aceptar(UUID usuarioId) {
        if (!estaPendiente()) {
            throw new IllegalStateException("La invitación no está pendiente");
        }
        this.aceptadaAt = Instant.now();
        this.aceptadaPorUsuarioId = usuarioId;
    }

    public void cancelar(UUID usuarioId) {
        if (aceptadaAt != null) {
            throw new IllegalStateException("Ya fue aceptada");
        }
        this.canceladaAt = Instant.now();
        this.canceladaPorUsuarioId = usuarioId;
    }

    public UUID getId() { return id; }
    public UUID getEmpresaId() { return empresaId; }
    public String getEmpresaRazonSocial() { return empresaRazonSocial; }
    public String getEmail() { return email; }
    public String getNombreInvitado() { return nombreInvitado; }
    public UsuarioEmpresa.Rol getRol() { return rol; }
    public String getToken() { return token; }
    public UUID getInvitadaPorUsuarioId() { return invitadaPorUsuarioId; }
    public Instant getCreadaAt() { return creadaAt; }
    public Instant getExpiraAt() { return expiraAt; }
    public Instant getAceptadaAt() { return aceptadaAt; }
    public Instant getCanceladaAt() { return canceladaAt; }
}
