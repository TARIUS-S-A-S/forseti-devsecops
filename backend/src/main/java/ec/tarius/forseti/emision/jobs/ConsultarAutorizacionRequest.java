package ec.tarius.forseti.emision.jobs;

import org.jobrunr.jobs.lambdas.JobRequest;
import org.jobrunr.jobs.lambdas.JobRequestHandler;

import java.util.UUID;

public record ConsultarAutorizacionRequest(
    UUID comprobanteId,
    UUID empresaId,
    String ambiente
) implements JobRequest {

    @Override
    @SuppressWarnings("rawtypes")
    public Class<? extends JobRequestHandler> getJobRequestHandler() {
        return ConsultarAutorizacionRequestHandler.class;
    }
}
