package ec.tarius.forseti.emision.jobs;

import ec.tarius.forseti.shared.tenant.TenantContext;
import org.jobrunr.jobs.lambdas.JobRequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Entry point del job de envío. JobRunr lo instancia vía Spring.
 *
 * Setea TenantContext ANTES de delegar a la lógica @Transactional — esto es lo que permite
 * que TenantTransactionAdvice (Order MAX_VALUE) corra dentro de la TX y emita SET LOCAL
 * app.empresa_id, activando las policies RLS para todos los SELECT/INSERT del worker.
 */
@Component
public class EnviarComprobanteRequestHandler implements JobRequestHandler<EnviarComprobanteRequest> {

    private static final Logger log = LoggerFactory.getLogger(EnviarComprobanteRequestHandler.class);

    private final EnvioJobOperations operations;

    public EnviarComprobanteRequestHandler(EnvioJobOperations operations) {
        this.operations = operations;
    }

    @Override
    public void run(EnviarComprobanteRequest req) {
        log.info("EnvioJob start comprobante_id={} empresa_id={} ambiente={}",
            req.comprobanteId(), req.empresaId(), req.ambiente());
        try {
            TenantContext.setEmpresaId(req.empresaId());
            operations.procesarEnvio(req.comprobanteId(), req.ambiente());
        } finally {
            TenantContext.clear();
        }
    }
}
