package ec.tarius.forseti.auth.tenant;

import ec.tarius.forseti.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Aspecto que setea SET LOCAL app.empresa_id y app.usuario_id en cada @Transactional.
 * Esto activa las RLS policies definidas en V4__rls_policies.sql.
 *
 * IMPORTANTE: corre DENTRO de la transacción (Order alto = se ejecuta cerca del @Transactional).
 * Si no hay tenant en contexto, NO setea — RLS bloqueará todas las queries tenant-aware.
 */
@Aspect
@Component
// @Transactional advice default Order = Integer.MAX_VALUE - 1 (LOWEST_PRECEDENCE - 1).
// Para que NUESTRO aspect corra DENTRO de la TX (después de @Transactional advice),
// necesita una precedencia MENOR (Order numéricamente MAYOR). Integer.MAX_VALUE garantiza eso.
// Bug previo: con MAX_VALUE - 100, este aspect corría AFUERA del @Transactional → SET LOCAL
// se ejecutaba sin TX activa, no afectaba a los INSERT del método de negocio.
@Order(Integer.MAX_VALUE)
public class TenantTransactionAdvice {

    private static final Logger log = LoggerFactory.getLogger(TenantTransactionAdvice.class);

    @PersistenceContext
    private EntityManager em;

    @Around("@annotation(org.springframework.transaction.annotation.Transactional) "
          + "|| @within(org.springframework.transaction.annotation.Transactional)")
    public Object setTenantContext(ProceedingJoinPoint pjp) throws Throwable {
        UUID empresaId = TenantContext.getEmpresaId();
        UUID usuarioId = TenantContext.getUsuarioId();

        // SET LOCAL solo dentro de una transacción activa
        // Se resetea automáticamente al COMMIT/ROLLBACK
        if (empresaId != null) {
            em.createNativeQuery("SELECT set_config('app.empresa_id', :v, true)")
                .setParameter("v", empresaId.toString())
                .getSingleResult();
        }
        if (usuarioId != null) {
            em.createNativeQuery("SELECT set_config('app.usuario_id', :v, true)")
                .setParameter("v", usuarioId.toString())
                .getSingleResult();
        }

        return pjp.proceed();
    }
}
