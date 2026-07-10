package ec.tarius.forseti.emision.jobs;

import org.jobrunr.jobs.lambdas.JobRequest;
import org.jobrunr.jobs.lambdas.JobRequestHandler;

import java.util.UUID;

/**
 * Payload del job de envío. JobRunr lo serializa a JSON y lo persiste en jobrunr_jobs.
 * El handler {@link EnviarComprobanteRequestHandler} lo consume.
 */
public record EnviarComprobanteRequest(
    UUID comprobanteId,
    UUID empresaId,
    String ambiente
) implements JobRequest {

    @Override
    @SuppressWarnings("rawtypes")
    public Class<? extends JobRequestHandler> getJobRequestHandler() {
        return EnviarComprobanteRequestHandler.class;
    }
}
