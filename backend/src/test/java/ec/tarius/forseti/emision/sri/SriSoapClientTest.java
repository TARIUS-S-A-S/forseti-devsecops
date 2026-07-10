package ec.tarius.forseti.emision.sri;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests del SriSoapClient contra WireMock con payloads que imitan los reales del SRI.
 *
 * Los XML de prueba reproducen el shape exacto que devuelven los WS offline del SRI
 * según la ficha técnica vigente — la idea es que si el SRI cambia algo y rompe el
 * cliente, estos tests gritan ANTES de que el cambio llegue a prod.
 */
class SriSoapClientTest {

    private static WireMockServer wm;
    private static SriEndpoints endpoints;
    private static SriSoapClient client;

    private static final String RUC = "1793235976001";
    // 49 dígitos — debe coincidir con la claveAccesoConsultada de los SOAP fixtures
    private static final String CLAVE_ACCESO = "2006202601179323597600110010010000000071234567819";

    @BeforeAll
    static void setUpAll() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();

        endpoints = new SriEndpoints();
        endpoints.setHttpTimeout(Duration.ofSeconds(5));
        SriEndpoints.Endpoints e = new SriEndpoints.Endpoints();
        e.setRecepcion("http://localhost:" + wm.port() + "/comprobantes-electronicos-ws/RecepcionComprobantesOffline");
        e.setAutorizacion("http://localhost:" + wm.port() + "/comprobantes-electronicos-ws/AutorizacionComprobantesOffline");
        endpoints.setPruebas(e);

        client = new SriSoapClient(endpoints);
    }

    @AfterAll
    static void tearDownAll() {
        wm.stop();
    }

    @BeforeEach
    void resetStubs() {
        wm.resetAll();
    }

    // ─────────────────────────────────────────────────────────────────────
    // RECEPCIÓN
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void recepcion_devuelve_RECIBIDA_cuando_sri_acepta() {
        wm.stubFor(post(urlPathEqualTo("/comprobantes-electronicos-ws/RecepcionComprobantesOffline"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml")
                .withBody(SOAP_RECEPCION_RECIBIDA)));

        RespuestaRecepcion r = client.recepcion("<factura/>".getBytes(), "PRUEBAS", CLAVE_ACCESO);

        assertThat(r.fueRecibida()).isTrue();
        assertThat(r.fueDevuelta()).isFalse();
        assertThat(r.claveAcceso()).isEqualTo(CLAVE_ACCESO);
        assertThat(r.mensajes()).isEmpty();
    }

    @Test
    void recepcion_devuelve_DEVUELTA_con_mensajes_cuando_xml_invalido() {
        wm.stubFor(post(urlPathEqualTo("/comprobantes-electronicos-ws/RecepcionComprobantesOffline"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml")
                .withBody(SOAP_RECEPCION_DEVUELTA)));

        RespuestaRecepcion r = client.recepcion("<factura/>".getBytes(), "PRUEBAS", CLAVE_ACCESO);

        assertThat(r.fueDevuelta()).isTrue();
        assertThat(r.mensajes()).hasSize(1);

        MensajeSri m = r.mensajes().get(0);
        assertThat(m.identificador()).isEqualTo("43");
        assertThat(m.mensaje()).contains("FIRMA INVALIDA");
        assertThat(m.tipo()).isEqualTo("ERROR");
        assertThat(m.esError()).isTrue();
        assertThat(m.resumen()).contains("FIRMA INVALIDA").contains("[43]");
    }

    @Test
    void recepcion_lanza_SriIOException_cuando_sri_devuelve_5xx() {
        wm.stubFor(post(urlPathEqualTo("/comprobantes-electronicos-ws/RecepcionComprobantesOffline"))
            .willReturn(aResponse().withStatus(503).withBody("Service Unavailable")));

        assertThatThrownBy(() -> client.recepcion("<factura/>".getBytes(), "PRUEBAS", CLAVE_ACCESO))
            .isInstanceOf(SriSoapClient.SriIOException.class)
            .hasMessageContaining("503");
    }

    @Test
    void recepcion_lanza_SriIOException_cuando_sri_tarda_demasiado() {
        wm.stubFor(post(urlPathEqualTo("/comprobantes-electronicos-ws/RecepcionComprobantesOffline"))
            .willReturn(aResponse().withStatus(200).withBody(SOAP_RECEPCION_RECIBIDA)
                .withFixedDelay(7_000)));  // 7s > 5s timeout

        assertThatThrownBy(() -> client.recepcion("<factura/>".getBytes(), "PRUEBAS", CLAVE_ACCESO))
            .isInstanceOf(SriSoapClient.SriIOException.class);
    }

    @Test
    void recepcion_lanza_SriProtocolException_cuando_respuesta_no_es_xml() {
        wm.stubFor(post(urlPathEqualTo("/comprobantes-electronicos-ws/RecepcionComprobantesOffline"))
            .willReturn(aResponse().withStatus(200).withBody("esto no es XML")));

        assertThatThrownBy(() -> client.recepcion("<factura/>".getBytes(), "PRUEBAS", CLAVE_ACCESO))
            .isInstanceOf(SriSoapClient.SriProtocolException.class);
    }

    @Test
    void recepcion_lanza_SriProtocolException_cuando_soap_fault_con_status_200() {
        // SRI puede devolver Faults SOAP con status 200 (envueltos en el envelope normal).
        // Esos sí son protocolo, no IO.
        wm.stubFor(post(urlPathEqualTo("/comprobantes-electronicos-ws/RecepcionComprobantesOffline"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml")
                .withBody(SOAP_FAULT)));

        assertThatThrownBy(() -> client.recepcion("<factura/>".getBytes(), "PRUEBAS", CLAVE_ACCESO))
            .isInstanceOf(SriSoapClient.SriProtocolException.class)
            .hasMessageContaining("Fault");
    }

    @Test
    void recepcion_lanza_SriProtocolException_cuando_falta_endpoint_configurado() {
        SriEndpoints vacios = new SriEndpoints();
        SriSoapClient sinUrls = new SriSoapClient(vacios);
        assertThatThrownBy(() -> sinUrls.recepcion(new byte[0], "PRUEBAS", CLAVE_ACCESO))
            .isInstanceOf(SriSoapClient.SriProtocolException.class)
            .hasMessageContaining("no configurada");
    }

    // ─────────────────────────────────────────────────────────────────────
    // AUTORIZACIÓN
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void autorizacion_devuelve_AUTORIZADO_con_xml_y_numero() {
        wm.stubFor(post(urlPathEqualTo("/comprobantes-electronicos-ws/AutorizacionComprobantesOffline"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml")
                .withBody(SOAP_AUTORIZACION_AUTORIZADO)));

        RespuestaAutorizacion r = client.autorizacion(CLAVE_ACCESO, "PRUEBAS");

        assertThat(r.fueAutorizado()).isTrue();
        assertThat(r.claveAccesoConsultada()).isEqualTo(CLAVE_ACCESO);
        assertThat(r.numeroAutorizacion()).isEqualTo(CLAVE_ACCESO);   // post-2024 == clave_acceso
        assertThat(r.fechaAutorizacion()).isNotNull();
        assertThat(r.ambiente()).isEqualTo("PRUEBAS");
        assertThat(r.xmlAutorizado()).isNotNull().isNotEmpty();
        assertThat(new String(r.xmlAutorizado())).contains("<factura");
    }

    @Test
    void autorizacion_devuelve_NO_AUTORIZADO_con_mensajes_de_rechazo() {
        wm.stubFor(post(urlPathEqualTo("/comprobantes-electronicos-ws/AutorizacionComprobantesOffline"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml")
                .withBody(SOAP_AUTORIZACION_NO_AUTORIZADO)));

        RespuestaAutorizacion r = client.autorizacion(CLAVE_ACCESO, "PRUEBAS");

        assertThat(r.fueRechazado()).isTrue();
        assertThat(r.mensajes()).isNotEmpty();
        assertThat(r.mensajes().get(0).mensaje()).contains("CLAVE ACCESO REGISTRADA");
    }

    @Test
    void autorizacion_devuelve_EN_PROCESAMIENTO_cuando_sri_todavia_no_termina() {
        wm.stubFor(post(urlPathEqualTo("/comprobantes-electronicos-ws/AutorizacionComprobantesOffline"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml")
                .withBody(SOAP_AUTORIZACION_EN_PROCESAMIENTO)));

        RespuestaAutorizacion r = client.autorizacion(CLAVE_ACCESO, "PRUEBAS");

        assertThat(r.estaEnProceso()).isTrue();
        assertThat(r.fueAutorizado()).isFalse();
    }

    @Test
    void autorizacion_devuelve_NO_ENCONTRADO_cuando_clave_no_existe_en_sri() {
        wm.stubFor(post(urlPathEqualTo("/comprobantes-electronicos-ws/AutorizacionComprobantesOffline"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml")
                .withBody(SOAP_AUTORIZACION_VACIA)));

        RespuestaAutorizacion r = client.autorizacion(CLAVE_ACCESO, "PRUEBAS");

        assertThat(r.estado()).isEqualTo(RespuestaAutorizacion.Estado.NO_ENCONTRADO);
        assertThat(r.numeroAutorizacion()).isNull();
        assertThat(r.xmlAutorizado()).isNull();
    }

    @Test
    void autorizacion_lanza_SriIOException_cuando_conexion_rechazada() {
        // Detenemos WireMock momentáneamente → ConnectException
        SriEndpoints rotos = new SriEndpoints();
        SriEndpoints.Endpoints e = new SriEndpoints.Endpoints();
        e.setRecepcion("http://localhost:1/recepcion");      // puerto cerrado
        e.setAutorizacion("http://localhost:1/autorizacion");
        rotos.setPruebas(e);
        rotos.setHttpTimeout(Duration.ofSeconds(2));

        SriSoapClient inalcanzable = new SriSoapClient(rotos);
        assertThatThrownBy(() -> inalcanzable.autorizacion(CLAVE_ACCESO, "PRUEBAS"))
            .isInstanceOf(SriSoapClient.SriIOException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // SELECCIÓN DE AMBIENTE
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void seleccion_ambiente_apunta_a_url_correcta() {
        SriEndpoints e = new SriEndpoints();
        SriEndpoints.Endpoints prue = new SriEndpoints.Endpoints();
        prue.setRecepcion("https://pruebas.sri/r");
        SriEndpoints.Endpoints prod = new SriEndpoints.Endpoints();
        prod.setRecepcion("https://prod.sri/r");
        e.setPruebas(prue);
        e.setProduccion(prod);

        assertThat(e.paraAmbiente("PRUEBAS").getRecepcion()).isEqualTo("https://pruebas.sri/r");
        assertThat(e.paraAmbiente("PRODUCCION").getRecepcion()).isEqualTo("https://prod.sri/r");
        // Default a pruebas si el ambiente es desconocido (defensivo)
        assertThat(e.paraAmbiente("OTRO").getRecepcion()).isEqualTo("https://pruebas.sri/r");
    }

    // ─────────────────────────────────────────────────────────────────────
    // SOAP fixtures (réplica de respuestas reales SRI)
    // ─────────────────────────────────────────────────────────────────────

    private static final String SOAP_RECEPCION_RECIBIDA = """
        <?xml version="1.0" encoding="UTF-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
          <soap:Body>
            <ns2:validarComprobanteResponse xmlns:ns2="http://ec.gob.sri.ws.recepcion">
              <RespuestaRecepcionComprobante>
                <estado>RECIBIDA</estado>
                <comprobantes/>
              </RespuestaRecepcionComprobante>
            </ns2:validarComprobanteResponse>
          </soap:Body>
        </soap:Envelope>
        """;

    private static final String SOAP_RECEPCION_DEVUELTA = """
        <?xml version="1.0" encoding="UTF-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
          <soap:Body>
            <ns2:validarComprobanteResponse xmlns:ns2="http://ec.gob.sri.ws.recepcion">
              <RespuestaRecepcionComprobante>
                <estado>DEVUELTA</estado>
                <comprobantes>
                  <comprobante>
                    <claveAcceso>2006202601179323597600110010010000000071234567819</claveAcceso>
                    <mensajes>
                      <mensaje>
                        <identificador>43</identificador>
                        <mensaje>FIRMA INVALIDA</mensaje>
                        <informacionAdicional>El certificado no corresponde al RUC del emisor</informacionAdicional>
                        <tipo>ERROR</tipo>
                      </mensaje>
                    </mensajes>
                  </comprobante>
                </comprobantes>
              </RespuestaRecepcionComprobante>
            </ns2:validarComprobanteResponse>
          </soap:Body>
        </soap:Envelope>
        """;

    private static final String SOAP_AUTORIZACION_AUTORIZADO = """
        <?xml version="1.0" encoding="UTF-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
          <soap:Body>
            <ns2:autorizacionComprobanteResponse xmlns:ns2="http://ec.gob.sri.ws.autorizacion">
              <RespuestaAutorizacionComprobante>
                <claveAccesoConsultada>2006202601179323597600110010010000000071234567819</claveAccesoConsultada>
                <numeroComprobantes>1</numeroComprobantes>
                <autorizaciones>
                  <autorizacion>
                    <estado>AUTORIZADO</estado>
                    <numeroAutorizacion>2006202601179323597600110010010000000071234567819</numeroAutorizacion>
                    <fechaAutorizacion>2026-06-20T15:30:45.000-05:00</fechaAutorizacion>
                    <ambiente>PRUEBAS</ambiente>
                    <comprobante>&lt;factura id="comprobante" version="2.1.0"&gt;&lt;/factura&gt;</comprobante>
                    <mensajes/>
                  </autorizacion>
                </autorizaciones>
              </RespuestaAutorizacionComprobante>
            </ns2:autorizacionComprobanteResponse>
          </soap:Body>
        </soap:Envelope>
        """;

    private static final String SOAP_AUTORIZACION_NO_AUTORIZADO = """
        <?xml version="1.0" encoding="UTF-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
          <soap:Body>
            <ns2:autorizacionComprobanteResponse xmlns:ns2="http://ec.gob.sri.ws.autorizacion">
              <RespuestaAutorizacionComprobante>
                <claveAccesoConsultada>2006202601179323597600110010010000000071234567819</claveAccesoConsultada>
                <numeroComprobantes>1</numeroComprobantes>
                <autorizaciones>
                  <autorizacion>
                    <estado>NO_AUTORIZADO</estado>
                    <ambiente>PRUEBAS</ambiente>
                    <mensajes>
                      <mensaje>
                        <identificador>70</identificador>
                        <mensaje>CLAVE ACCESO REGISTRADA</mensaje>
                        <informacionAdicional>Ya existe un comprobante autorizado con esta clave</informacionAdicional>
                        <tipo>ERROR</tipo>
                      </mensaje>
                    </mensajes>
                  </autorizacion>
                </autorizaciones>
              </RespuestaAutorizacionComprobante>
            </ns2:autorizacionComprobanteResponse>
          </soap:Body>
        </soap:Envelope>
        """;

    private static final String SOAP_AUTORIZACION_EN_PROCESAMIENTO = """
        <?xml version="1.0" encoding="UTF-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
          <soap:Body>
            <ns2:autorizacionComprobanteResponse xmlns:ns2="http://ec.gob.sri.ws.autorizacion">
              <RespuestaAutorizacionComprobante>
                <claveAccesoConsultada>2006202601179323597600110010010000000071234567819</claveAccesoConsultada>
                <numeroComprobantes>1</numeroComprobantes>
                <autorizaciones>
                  <autorizacion>
                    <estado>EN_PROCESAMIENTO</estado>
                    <ambiente>PRUEBAS</ambiente>
                    <mensajes/>
                  </autorizacion>
                </autorizaciones>
              </RespuestaAutorizacionComprobante>
            </ns2:autorizacionComprobanteResponse>
          </soap:Body>
        </soap:Envelope>
        """;

    private static final String SOAP_AUTORIZACION_VACIA = """
        <?xml version="1.0" encoding="UTF-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
          <soap:Body>
            <ns2:autorizacionComprobanteResponse xmlns:ns2="http://ec.gob.sri.ws.autorizacion">
              <RespuestaAutorizacionComprobante>
                <claveAccesoConsultada>2006202601179323597600110010010000000071234567819</claveAccesoConsultada>
                <numeroComprobantes>0</numeroComprobantes>
                <autorizaciones/>
              </RespuestaAutorizacionComprobante>
            </ns2:autorizacionComprobanteResponse>
          </soap:Body>
        </soap:Envelope>
        """;

    private static final String SOAP_FAULT = """
        <?xml version="1.0" encoding="UTF-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
          <soap:Body>
            <soap:Fault>
              <faultcode>soap:Server</faultcode>
              <faultstring>Internal error</faultstring>
            </soap:Fault>
          </soap:Body>
        </soap:Envelope>
        """;
}
