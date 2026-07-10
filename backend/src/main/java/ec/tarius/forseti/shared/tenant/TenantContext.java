package ec.tarius.forseti.shared.tenant;

import java.util.UUID;

/**
 * Contexto de tenant para el request actual. ThreadLocal por request.
 * Movido de auth/tenant a shared/tenant para romper ciclo de dependencias
 * (AuditLogger lo necesita y vive en shared).
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> EMPRESA_ID = new ThreadLocal<>();
    private static final ThreadLocal<UUID> USUARIO_ID = new ThreadLocal<>();

    private TenantContext() {}

    public static void setEmpresaId(UUID empresaId) { EMPRESA_ID.set(empresaId); }
    public static UUID getEmpresaId() { return EMPRESA_ID.get(); }
    public static void setUsuarioId(UUID usuarioId) { USUARIO_ID.set(usuarioId); }
    public static UUID getUsuarioId() { return USUARIO_ID.get(); }
    public static void clear() {
        EMPRESA_ID.remove();
        USUARIO_ID.remove();
    }
}
