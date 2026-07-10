package ec.tarius.forseti.auth.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usuario_empresa")
public class UsuarioEmpresa {

    public enum Rol {
        DUENO, CONTADORA, EMPLEADO, ADMIN
    }

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "usuario_id", nullable = false)
    private UUID usuarioId;

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rol", nullable = false, length = 20)
    private Rol rol;

    @Column(name = "invitado_por_usuario_id")
    private UUID invitadoPorUsuarioId;

    @Column(name = "aceptado_at")
    private Instant aceptadoAt;

    @Column(name = "creado_at", nullable = false, updatable = false)
    private Instant creadoAt = Instant.now();

    protected UsuarioEmpresa() {}

    public static UsuarioEmpresa nueva(UUID usuarioId, UUID empresaId, Rol rol) {
        UsuarioEmpresa ue = new UsuarioEmpresa();
        ue.usuarioId = usuarioId;
        ue.empresaId = empresaId;
        ue.rol = rol;
        ue.aceptadoAt = Instant.now(); // auto-accept para self-register
        return ue;
    }

    public UUID getId() { return id; }
    public UUID getUsuarioId() { return usuarioId; }
    public UUID getEmpresaId() { return empresaId; }
    public Rol getRol() { return rol; }
    public Instant getAceptadoAt() { return aceptadoAt; }

    public void setRol(Rol rol) { this.rol = rol; }
}
