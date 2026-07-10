package ec.tarius.forseti.emision.sri;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Health check periódico del WS del SRI Ecuador.
 *
 * Cómo funciona: cada {@link #INTERVALO_MS} llamamos al WS de autorización con una clave de
 * acceso aleatoria que sabemos NO existe. El SRI responde "NO_ENCONTRADO" instantáneamente —
 * eso confirma que el servicio está vivo sin emitir nada real.
 *
 * Resultado:
 *   - {@link SriHealthCheck.Estado#ARRIBA}    si latencia < 5s.
 *   - {@link SriHealthCheck.Estado#DEGRADADO} si latencia entre 5s y timeout.
 *   - {@link SriHealthCheck.Estado#CAIDO}     si timeout o conexión rechazada.
 *
 * El estado se persiste en {@code sri_health_check} (no tenant-aware) y se expone vía el
 * endpoint público {@code GET /api/v1/sri/estado}. El frontend lo lee al cargar la app y
 * muestra un banner si está CAIDO o DEGRADADO.
 */
@Service
public class SriHealthCheckService {

    private static final Logger log = LoggerFactory.getLogger(SriHealthCheckService.class);

    /** 5 minutos en ms. Suficiente para detectar caídas; bajo para no martillar al SRI. */
    private static final long INTERVALO_MS = 300_000L;

    /** Latencia que consideramos "degradada" (umbral entre ARRIBA y DEGRADADO). */
    private static final int UMBRAL_DEGRADADO_MS = 5_000;

    /** Clave de acceso ficticia para ping (49 dígitos pero que NO corresponde a comprobante real). */
    private static final String CLAVE_PING = "0000000000000000000000000000000000000000000000000";

    private final SriSoapClient sriClient;
    private final SriHealthCheckRepository repo;

    public SriHealthCheckService(SriSoapClient sriClient, SriHealthCheckRepository repo) {
        this.sriClient = sriClient;
        this.repo = repo;
    }

    /**
     * Pinga ambos ambientes cada 5 minutos. Con initialDelay para no martillar al arrancar.
     * Si necesitamos pausarlo (mantenimiento), poner forseti.sri.health-check.enabled=false
     * en application.yml — pero por ahora corre siempre.
     */
    @Scheduled(fixedRate = INTERVALO_MS, initialDelay = 30_000)
    public void pingear() {
        for (String ambiente : new String[]{"PRUEBAS", "PRODUCCION"}) {
            try {
                pingearAmbiente(ambiente);
            } catch (Exception e) {
                log.warn("Health check SRI {} excepción no manejada: {}", ambiente, e.getMessage());
            }
        }
    }

    @Transactional
    public void pingearAmbiente(String ambiente) {
        long ini = System.currentTimeMillis();
        SriHealthCheck check;
        try {
            // Consulta autorización por clave inexistente. El SRI devuelve NO_ENCONTRADO
            // (o similar) en pocos ms. Nos interesa que RESPONDA, no qué responde.
            sriClient.autorizacion(CLAVE_PING, ambiente);
            int latencia = (int) (System.currentTimeMillis() - ini);
            if (latencia >= UMBRAL_DEGRADADO_MS) {
                check = SriHealthCheck.degradado(ambiente, latencia,
                    "Latencia " + latencia + "ms supera umbral " + UMBRAL_DEGRADADO_MS + "ms");
                log.warn("SRI {} DEGRADADO ({}ms)", ambiente, latencia);
            } else {
                check = SriHealthCheck.arriba(ambiente, latencia);
                log.debug("SRI {} ARRIBA ({}ms)", ambiente, latencia);
            }
        } catch (SriSoapClient.SriCircuitOpenException e) {
            // El circuito ya estaba abierto — no es un ping real, pero refleja estado caído.
            check = SriHealthCheck.caido(ambiente, "Circuit breaker abierto: " + e.getMessage());
            log.warn("SRI {} CAIDO (circuit breaker)", ambiente);
        } catch (SriSoapClient.SriIOException e) {
            check = SriHealthCheck.caido(ambiente, "IO: " + e.getMessage());
            log.warn("SRI {} CAIDO: {}", ambiente, e.getMessage());
        } catch (SriSoapClient.SriProtocolException e) {
            // Respondió pero raro — consideramos degradado.
            check = SriHealthCheck.degradado(ambiente,
                (int) (System.currentTimeMillis() - ini),
                "Respuesta no parseable: " + e.getMessage());
            log.warn("SRI {} DEGRADADO (protocol): {}", ambiente, e.getMessage());
        }
        repo.save(check);
    }
}
