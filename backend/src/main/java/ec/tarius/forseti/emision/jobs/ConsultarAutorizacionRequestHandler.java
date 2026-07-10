package ec.tarius.forseti.emision.jobs;

import ec.tarius.forseti.shared.tenant.TenantContext;
import org.jobrunr.jobs.lambdas.JobRequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ConsultarAutorizacionRequestHandler implements JobRequestHandler<ConsultarAutorizacionRequest> {

    private static final Logger log = LoggerFactory.getLogger(ConsultarAutorizacionRequestHandler.class);

    private final AutorizacionJobOperations operations;

    public ConsultarAutorizacionRequestHandler(AutorizacionJobOperations operations) {
        this.operations = operations;
    }

    @Override
    public void run(ConsultarAutorizacionRequest req) {
        log.info("AutorizacionJob start comprobante_id={} ambiente={}",
            req.comprobanteId(), req.ambiente());
        try {
            TenantContext.setEmpresaId(req.empresaId());
            operations.consultar(req.comprobanteId(), req.ambiente());
        } finally {
            TenantContext.clear();
        }
    }
}
