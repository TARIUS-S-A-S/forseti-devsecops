package ec.tarius.forseti.compras;

import ec.tarius.forseti.compras.dto.ComprasDtos.FlujoCajaResponse;
import ec.tarius.forseti.compras.service.ReportesService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * HU-F4 (flujo de caja) + HU-F5 (exports CSV). Endpoints públicos al usuario autenticado
 * de la empresa activa (RLS filtra).
 */
@RestController
@RequestMapping("/api/v1/reportes")
public class ReportesController {

    private final ReportesService service;

    public ReportesController(ReportesService service) {
        this.service = service;
    }

    @GetMapping("/flujo-caja")
    @PreAuthorize("isAuthenticated()")
    public FlujoCajaResponse flujoCaja(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return service.flujoCaja(desde, hasta);
    }

    @GetMapping("/compras.csv")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> exportCompras(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        byte[] csv = service.exportCsvCompras(desde, hasta);
        return csvResponse(csv, "compras", desde, hasta);
    }

    @GetMapping("/ventas.csv")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> exportVentas(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        byte[] csv = service.exportCsvVentas(desde, hasta);
        return csvResponse(csv, "ventas", desde, hasta);
    }

    /**
     * Construye la response del CSV con headers correctos.
     * Usa {@link ContentDisposition#attachment} (NO formData — eso es para multipart).
     * Si no se especifican fechas, default = mes actual (mismo criterio que el service).
     */
    private static ResponseEntity<byte[]> csvResponse(byte[] csv, String base,
                                                       LocalDate desde, LocalDate hasta) {
        LocalDate d = desde != null ? desde : LocalDate.now().withDayOfMonth(1);
        LocalDate h = hasta != null ? hasta : LocalDate.now();
        String filename = base + "-" + d + "_" + h + ".csv";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.setContentDisposition(ContentDisposition.attachment()
            .filename(filename, StandardCharsets.UTF_8)
            .build());
        return ResponseEntity.ok().headers(headers).body(csv);
    }
}
