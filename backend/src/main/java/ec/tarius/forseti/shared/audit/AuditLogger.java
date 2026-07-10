package ec.tarius.forseti.shared.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.tarius.forseti.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Registra eventos en la tabla auditoria.
 * Async para no bloquear el request. Si falla, log y sigue.
 *
 * Retención 7 años (obligación SRI/LOPDP) — cleanup job posterior.
 */
@Service
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

    @PersistenceContext
    private EntityManager em;

    private final ObjectMapper mapper = new ObjectMapper();

    @Async
    @Transactional
    public void log(String accion, String recursoTipo, UUID recursoId, Map<String, Object> detalles, String ip, String userAgent) {
        try {
            String jsonDetalles = detalles != null ? mapper.writeValueAsString(detalles) : null;
            em.createNativeQuery(
                "INSERT INTO auditoria (usuario_id, empresa_id, accion, recurso_tipo, recurso_id, detalles, ip, user_agent) " +
                "VALUES (:userId, :empresaId, :accion, :recursoTipo, :recursoId, CAST(:detalles AS jsonb), :ip, :userAgent)"
            )
            .setParameter("userId", TenantContext.getUsuarioId())
            .setParameter("empresaId", TenantContext.getEmpresaId())
            .setParameter("accion", accion)
            .setParameter("recursoTipo", recursoTipo)
            .setParameter("recursoId", recursoId)
            .setParameter("detalles", jsonDetalles)
            .setParameter("ip", ip)
            .setParameter("userAgent", userAgent)
            .executeUpdate();
        } catch (JsonProcessingException e) {
            log.error("Error serializando detalles audit", e);
        } catch (Exception e) {
            // NO romper el flujo principal si auditoria falla
            log.error("Error guardando auditoria accion={}", accion, e);
        }
    }

    @Async
    @Transactional
    public void logSimple(String accion, String ip, String userAgent) {
        log(accion, null, null, null, ip, userAgent);
    }

    /**
     * Variante simplificada para servicios — toma el HTTP context del TenantContext.
     * Útil cuando no se tiene HttpServletRequest a mano. ip/userAgent quedan null.
     */
    @Async
    @Transactional
    public void log(String accion, String recursoTipo, UUID recursoId, String detalle) {
        log(accion, recursoTipo, recursoId,
            detalle != null ? Map.of("detalle", detalle) : null,
            null, null);
    }
}
