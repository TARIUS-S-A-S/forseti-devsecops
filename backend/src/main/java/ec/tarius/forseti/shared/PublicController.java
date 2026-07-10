package ec.tarius.forseti.shared;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/public")
public class PublicController {

    /**
     * Info pública mínima. Se cachea 5 min para no thrashear el endpoint con health checks.
     */
    @GetMapping(value = "/info", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll()")
    public ResponseEntity<PublicInfo> info() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
            .body(new PublicInfo(
                "Forseti",
                "0.1.0-SNAPSHOT",
                "TARIUS S.A.S",
                "SaaS de facturación electrónica SRI Ecuador",
                Instant.now()
            ));
    }

    /**
     * DTO tipado (record) — Sprint 1: anotar con @Schema para OpenAPI.
     */
    public record PublicInfo(
        String product,
        String version,
        String company,
        String purpose,
        Instant generatedAt
    ) {}
}
