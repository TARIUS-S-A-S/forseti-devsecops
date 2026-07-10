package ec.tarius.forseti.emision.sri;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Estado actual del WS del SRI Ecuador. Consumido por la UI para mostrar un banner
 * "SRI no disponible — tus facturas están firmadas y se enviarán cuando se restablezca".
 *
 * Endpoint PÚBLICO (sin auth) porque la UI lo lee también en la pantalla de login y
 * lo refresca cada N segundos. No expone datos sensibles — solo arriba/caído + latencia.
 */
@RestController
@RequestMapping("/api/v1/sri")
public class SriEstadoController {

    private final SriHealthCheckRepository healthRepo;
    private final SriSoapClient sriClient;

    public SriEstadoController(SriHealthCheckRepository healthRepo, SriSoapClient sriClient) {
        this.healthRepo = healthRepo;
        this.sriClient = sriClient;
    }

    @GetMapping(value = "/estado", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll()")
    public ResponseEntity<EstadoSriResponse> estado() {
        return ResponseEntity.ok(new EstadoSriResponse(
            estadoAmbiente("PRUEBAS"),
            estadoAmbiente("PRODUCCION"),
            Instant.now()
        ));
    }

    private EstadoAmbiente estadoAmbiente(String ambiente) {
        SriSoapClient.EstadoCircuito cb = sriClient.estadoCircuito(ambiente);
        var ultimoHc = healthRepo.ultimoPor(ambiente).orElse(null);
        String estado = ultimoHc != null ? ultimoHc.getEstado() : "DESCONOCIDO";
        return new EstadoAmbiente(
            ambiente,
            estado,
            ultimoHc != null ? ultimoHc.getLatenciaMs() : null,
            ultimoHc != null ? ultimoHc.getMensaje() : null,
            ultimoHc != null ? ultimoHc.getTsCheck() : null,
            cb.getModo().name(),
            cb.getFallosConsecutivos()
        );
    }

    public record EstadoSriResponse(
        EstadoAmbiente pruebas,
        EstadoAmbiente produccion,
        Instant consultadoEn
    ) {}

    public record EstadoAmbiente(
        String ambiente,
        String estado,         // ARRIBA | DEGRADADO | CAIDO | DESCONOCIDO
        Integer latenciaMs,    // null si nunca se chequeó o está caído
        String mensaje,        // null si todo OK
        Instant ultimoCheck,   // null si nunca se chequeó
        String circuitBreaker, // CLOSED | OPEN | HALF_OPEN
        int fallosConsecutivos
    ) {}
}
