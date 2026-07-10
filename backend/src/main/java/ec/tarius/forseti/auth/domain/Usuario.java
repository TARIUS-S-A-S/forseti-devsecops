package ec.tarius.forseti.auth.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usuario")
public class Usuario {

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "email", columnDefinition = "citext")
    private String email;

    @Column(name = "username", columnDefinition = "citext")
    private String username;

    @Column(name = "nombre", nullable = false)
    private String nombre;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "email_verificado_at")
    private Instant emailVerificadoAt;

    @Column(name = "activo", nullable = false)
    private boolean activo = true;

    @Column(name = "verificacion_token")
    private String verificacionToken;

    @Column(name = "verificacion_token_expira_at")
    private Instant verificacionTokenExpiraAt;

    @Column(name = "recovery_token")
    private String recoveryToken;

    @Column(name = "recovery_token_expira_at")
    private Instant recoveryTokenExpiraAt;

    @Column(name = "totp_secret")
    private String totpSecret;

    @Column(name = "totp_activado_at")
    private Instant totpActivadoAt;

    @Column(name = "totp_login_required", nullable = false)
    private boolean totpLoginRequired = true;

    @Column(name = "debe_cambiar_password", nullable = false)
    private boolean debeCambiarPassword = false;

    @Column(name = "intentos_fallidos", nullable = false)
    private int intentosFallidos = 0;

    @Column(name = "bloqueado_hasta")
    private Instant bloqueadoHasta;

    @Column(name = "ultimo_login_at")
    private Instant ultimoLoginAt;

    @Column(name = "ultimo_login_ip")
    private String ultimoLoginIp;

    @Column(name = "creado_at", nullable = false, updatable = false)
    private Instant creadoAt = Instant.now();

    @Column(name = "actualizado_at", nullable = false)
    private Instant actualizadoAt = Instant.now();

    protected Usuario() {}

    public static Usuario nuevo(String email, String nombre, String passwordHash) {
        Usuario u = new Usuario();
        u.email = email.toLowerCase();
        u.nombre = nombre;
        u.passwordHash = passwordHash;
        return u;
    }

    /**
     * Crea un usuario con username (sin email). Se auto-verifica (no hay email a confirmar)
     * pero queda con debeCambiarPassword=true para forzar el cambio al primer login.
     * Lo usa el DUEÑO de una empresa al dar de alta a un empleado sin correo.
     */
    public static Usuario nuevoConUsername(String username, String nombre, String passwordHashTemporal) {
        Usuario u = new Usuario();
        u.username = username.toLowerCase();
        u.nombre = nombre;
        u.passwordHash = passwordHashTemporal;
        u.emailVerificadoAt = Instant.now();   // no hay email a verificar
        u.debeCambiarPassword = true;
        return u;
    }

    public boolean isEmailVerificado() { return emailVerificadoAt != null; }
    public boolean isTotpActivo() { return totpActivadoAt != null; }
    /** True si está activo Y debe pedir código al loguearse. False = 2FA activo pero pausado para login. */
    public boolean isTotpRequeridoEnLogin() { return isTotpActivo() && totpLoginRequired; }
    public boolean isTotpLoginRequired() { return totpLoginRequired; }
    public boolean isBloqueado() {
        return bloqueadoHasta != null && bloqueadoHasta.isAfter(Instant.now());
    }

    public void pausarTotpLogin() { this.totpLoginRequired = false; }
    public void reanudarTotpLogin() { this.totpLoginRequired = true; }

    /** Para usuarios que se registran self-service con username opcional. */
    public void asignarUsername(String username) { this.username = username == null ? null : username.toLowerCase(); }

    public void marcarEmailVerificado() {
        this.emailVerificadoAt = Instant.now();
        this.verificacionToken = null;
        this.verificacionTokenExpiraAt = null;
    }

    public void asignarTokenVerificacion(String token, Instant expiraAt) {
        this.verificacionToken = token;
        this.verificacionTokenExpiraAt = expiraAt;
    }

    public void asignarTokenRecovery(String token, Instant expiraAt) {
        this.recoveryToken = token;
        this.recoveryTokenExpiraAt = expiraAt;
    }

    public void cambiarPassword(String nuevoHash) {
        this.passwordHash = nuevoHash;
        this.recoveryToken = null;
        this.recoveryTokenExpiraAt = null;
        this.intentosFallidos = 0;
        this.bloqueadoHasta = null;
        this.debeCambiarPassword = false;   // ya cambió, todo bien
    }

    public void marcarDebeCambiarPassword() {
        this.debeCambiarPassword = true;
    }

    public void activarTotp(String secret) {
        this.totpSecret = secret;
        this.totpActivadoAt = Instant.now();
    }

    public void desactivarTotp() {
        this.totpSecret = null;
        this.totpActivadoAt = null;
    }

    public void registrarLoginExitoso(String ip) {
        this.ultimoLoginAt = Instant.now();
        this.ultimoLoginIp = ip;
        this.intentosFallidos = 0;
        this.bloqueadoHasta = null;
    }

    public void registrarIntentoFallido(int maxAntesDeBloquear, int minutosBloqueo) {
        this.intentosFallidos += 1;
        if (this.intentosFallidos >= maxAntesDeBloquear) {
            this.bloqueadoHasta = Instant.now().plusSeconds(60L * minutosBloqueo);
        }
    }

    // Getters
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getUsername() { return username; }
    public boolean isDebeCambiarPassword() { return debeCambiarPassword; }
    public String getNombre() { return nombre; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getEmailVerificadoAt() { return emailVerificadoAt; }
    public boolean isActivo() { return activo; }
    public String getVerificacionToken() { return verificacionToken; }
    public Instant getVerificacionTokenExpiraAt() { return verificacionTokenExpiraAt; }
    public String getRecoveryToken() { return recoveryToken; }
    public Instant getRecoveryTokenExpiraAt() { return recoveryTokenExpiraAt; }
    public String getTotpSecret() { return totpSecret; }
    public Instant getTotpActivadoAt() { return totpActivadoAt; }
    public int getIntentosFallidos() { return intentosFallidos; }
    public Instant getBloqueadoHasta() { return bloqueadoHasta; }
    public Instant getUltimoLoginAt() { return ultimoLoginAt; }

    // Setters mínimos requeridos
    public void setNombre(String nombre) { this.nombre = nombre; }
    public void setActivo(boolean activo) { this.activo = activo; }
}
