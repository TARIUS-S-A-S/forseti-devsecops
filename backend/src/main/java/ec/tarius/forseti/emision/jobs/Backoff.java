package ec.tarius.forseti.emision.jobs;

import java.time.Duration;

/**
 * Backoff exponencial para reintentos al SRI. Después del intento N, el siguiente espera:
 *   N=1 →   30 s
 *   N=2 →    2 min
 *   N=3 →   10 min
 *   N=4 →   30 min
 *   N=5 →    2 h
 *   N>5 →   ABANDONAR (no más reintentos)
 */
final class Backoff {

    static final int MAX_INTENTOS = 5;

    private Backoff() {}

    static Duration siguiente(int intentosYaHechos) {
        return switch (intentosYaHechos) {
            case 0, 1 -> Duration.ofSeconds(30);
            case 2 -> Duration.ofMinutes(2);
            case 3 -> Duration.ofMinutes(10);
            case 4 -> Duration.ofMinutes(30);
            default -> Duration.ofHours(2);
        };
    }

    static boolean debeAbandonar(int intentos) {
        return intentos >= MAX_INTENTOS;
    }
}
