package ec.tarius.forseti.emision.sri;

import ec.tarius.forseti.emision.ComprobanteRepository;
import ec.tarius.forseti.shared.tenant.TenantContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dashboard de la cola de comprobantes hacia el SRI para la empresa activa.
 *
 * Pensado para una vista admin/operación: cuántos comprobantes están esperando, cuál es el
 * más viejo, tasa de éxito últimas 24h, latencia promedio. Lo lee Forseti UI para mostrar
 * un dashboard básico en la vista de comprobantes.
 */
@RestController
@RequestMapping("/api/v1/sri")
public class ColaSriController {

    private final ComprobanteRepository comprobanteRepo;

    public ColaSriController(ComprobanteRepository comprobanteRepo) {
        this.comprobanteRepo = comprobanteRepo;
    }

    @GetMapping(value = "/cola", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ColaResponse> cola() {
        UUID empresaId = TenantContext.getEmpresaId();
        if (empresaId == null) {
            return ResponseEntity.noContent().build();
        }

        Map<String, Long> conteoPorEstado = new HashMap<>();
        comprobanteRepo.conteoPorEstado(empresaId)
            .forEach(c -> conteoPorEstado.put(c.getEstado(), c.getTotal()));

        Instant ahora = Instant.now();
        Instant hace24h = ahora.minus(Duration.ofHours(24));

        Instant masViejo = comprobanteRepo.masViejoPendiente(empresaId).orElse(null);
        Long antiguedadSeg = masViejo == null ? null
            : Duration.between(masViejo, ahora).getSeconds();

        long total24h = comprobanteRepo.total24h(empresaId, hace24h);
        long autorizados24h = comprobanteRepo.autorizados24h(empresaId, hace24h);
        Double tasaExito = total24h == 0 ? null
            : (double) autorizados24h / (double) total24h;

        Double tiempoPromSeg = comprobanteRepo.segundosPromedioAutorizacion24h(empresaId, hace24h);

        return ResponseEntity.ok(new ColaResponse(
            conteoPorEstado,
            antiguedadSeg,
            total24h,
            autorizados24h,
            tasaExito,
            tiempoPromSeg,
            ahora
        ));
    }

    public record ColaResponse(
        /** Mapa estado→cantidad. Estados posibles: BORRADOR, FIRMADA, ENVIADA, EN_PROCESO,
         *  AUTORIZADA, DEVUELTA, NO_AUTORIZADA, ABANDONADA, CANCELADA. */
        Map<String, Long> porEstado,

        /** Segundos desde que el comprobante más viejo pendiente fue creado. null si no hay
         *  pendientes. Útil para alertar si > 1h (algo está mal). */
        Long antiguedadMaxPendienteSeg,

        /** Total de comprobantes creados en las últimas 24h. */
        long total24h,

        /** De los total24h, cuántos llegaron a AUTORIZADA. */
        long autorizados24h,

        /** Tasa de éxito últimas 24h ∈ [0, 1]. null si total24h=0. */
        Double tasaExito24h,

        /** Tiempo promedio en segundos entre creación y autorización SRI, últimas 24h. */
        Double tiempoPromAutorizacionSeg,

        Instant consultadoEn
    ) {}
}
