package ec.tarius.forseti.emision.sri;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * URLs de los WS del SRI por ambiente. Se configuran en application.yml:
 *
 *   forseti.sri.pruebas.recepcion: https://celcer.sri.gob.ec/.../RecepcionComprobantesOffline
 *   forseti.sri.pruebas.autorizacion: https://celcer.sri.gob.ec/.../AutorizacionComprobantesOffline
 *   forseti.sri.produccion.recepcion: https://cel.sri.gob.ec/.../RecepcionComprobantesOffline
 *   forseti.sri.produccion.autorizacion: https://cel.sri.gob.ec/.../AutorizacionComprobantesOffline
 *
 * Para tests (WireMock) se sobreescriben en application-test.yml o vía @DynamicPropertySource.
 */
@ConfigurationProperties(prefix = "forseti.sri")
public class SriEndpoints {

    private Endpoints pruebas = new Endpoints();
    private Endpoints produccion = new Endpoints();
    private Duration httpTimeout = Duration.ofSeconds(30);

    public Endpoints getPruebas() { return pruebas; }
    public void setPruebas(Endpoints pruebas) { this.pruebas = pruebas; }

    public Endpoints getProduccion() { return produccion; }
    public void setProduccion(Endpoints produccion) { this.produccion = produccion; }

    public Duration getHttpTimeout() { return httpTimeout; }
    public void setHttpTimeout(Duration httpTimeout) { this.httpTimeout = httpTimeout; }

    /** Selecciona el par de URLs según el ambiente del comprobante. */
    public Endpoints paraAmbiente(String ambiente) {
        return "PRODUCCION".equalsIgnoreCase(ambiente) ? produccion : pruebas;
    }

    public static class Endpoints {
        private String recepcion;
        private String autorizacion;

        public String getRecepcion() { return recepcion; }
        public void setRecepcion(String recepcion) { this.recepcion = recepcion; }

        public String getAutorizacion() { return autorizacion; }
        public void setAutorizacion(String autorizacion) { this.autorizacion = autorizacion; }
    }
}
