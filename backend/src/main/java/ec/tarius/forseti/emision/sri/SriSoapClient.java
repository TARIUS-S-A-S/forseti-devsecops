package ec.tarius.forseti.emision.sri;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cliente SOAP para los WS offline del SRI (recepción + autorización).
 *
 * Diseño:
 *   - HttpClient5 directo (sin CXF) — RAM mínima, control total del payload.
 *   - SOAP envelope armado a mano por string (los WS SRI son simples: 1 método, 1 parámetro).
 *   - Response parseada con DOM + XPath manual (también simple, evita JAXB).
 *   - Excepciones:
 *       · {@link SriIOException}      → transient: timeout, conexión, 5xx. Reintentar.
 *       · {@link SriProtocolException} → permanent: respuesta SOAP malformada. Bug, no reintentar.
 *     DEVUELTA y NO_AUTORIZADO NO son excepciones — son resultados normales del negocio.
 *   - NO se loguea el XML firmado completo (lleva la firma — basta con claveAcceso y estado).
 */
@Component
public class SriSoapClient {

    private static final Logger log = LoggerFactory.getLogger(SriSoapClient.class);

    private static final String NS_RECEPCION = "http://ec.gob.sri.ws.recepcion";
    private static final String NS_AUTORIZACION = "http://ec.gob.sri.ws.autorizacion";

    // ─────────────────────────────────────────────────────────────────────
    // Circuit breaker simple por ambiente.
    //
    // Cuando se acumulan {@link #UMBRAL_FALLOS} fallos consecutivos de red contra el SRI,
    // abrimos el circuito y rechazamos llamadas durante {@link #COOLDOWN}. Pasado ese
    // tiempo entra en HALF_OPEN: deja pasar 1 request — si OK se cierra, si falla vuelve
    // a OPEN. Evita que cuando el SRI se cae, 200 jobs reintentando cada 30s saturen
    // nuestro VPS y el propio SRI.
    //
    // El estado por ambiente vive en RAM (volatile/atomic). Como el backend corre en
    // una sola instancia (1 VPS), no necesitamos sincronizar entre nodos.
    // ─────────────────────────────────────────────────────────────────────
    private static final int UMBRAL_FALLOS = 5;
    private static final Duration COOLDOWN = Duration.ofMinutes(1);

    private final ConcurrentMap<String, EstadoCircuito> circuitos = new ConcurrentHashMap<>();

    private final SriEndpoints endpoints;

    public SriSoapClient(SriEndpoints endpoints) {
        this.endpoints = endpoints;
    }

    /** Estado público del circuito para un ambiente. Útil para health checks y dashboard. */
    public EstadoCircuito estadoCircuito(String ambiente) {
        return circuitos.computeIfAbsent(ambiente, k -> new EstadoCircuito());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Recepción
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Envía el XML firmado al WS de recepción. Devuelve RECIBIDA o DEVUELTA con los mensajes.
     */
    public RespuestaRecepcion recepcion(byte[] xmlFirmado, String ambiente, String claveAcceso) {
        EstadoCircuito cb = estadoCircuito(ambiente);
        cb.permitirOLanzar(ambiente);

        String url = endpoints.paraAmbiente(ambiente).getRecepcion();
        if (url == null || url.isBlank()) {
            throw new SriProtocolException("URL de recepción SRI no configurada para ambiente " + ambiente);
        }

        String xmlB64 = Base64.getEncoder().encodeToString(xmlFirmado);
        String envelope = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ec="%s">
              <soapenv:Header/>
              <soapenv:Body>
                <ec:validarComprobante>
                  <xml>%s</xml>
                </ec:validarComprobante>
              </soapenv:Body>
            </soapenv:Envelope>
            """.formatted(NS_RECEPCION, xmlB64);

        log.info("SRI recepción → {} clave_acceso={}", url, claveAcceso);
        try {
            String responseBody = postSoap(url, envelope, "");
            RespuestaRecepcion r = parsearRecepcion(responseBody, claveAcceso);
            cb.registrarExito();
            return r;
        } catch (SriIOException e) {
            cb.registrarFallo();
            throw e;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Autorización
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Consulta el estado de autorización por clave de acceso.
     * Devuelve AUTORIZADO + xml autorizado, NO_AUTORIZADO, EN_PROCESAMIENTO o NO_ENCONTRADO.
     */
    public RespuestaAutorizacion autorizacion(String claveAcceso, String ambiente) {
        EstadoCircuito cb = estadoCircuito(ambiente);
        cb.permitirOLanzar(ambiente);

        String url = endpoints.paraAmbiente(ambiente).getAutorizacion();
        if (url == null || url.isBlank()) {
            throw new SriProtocolException("URL de autorización SRI no configurada para ambiente " + ambiente);
        }

        String envelope = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ec="%s">
              <soapenv:Header/>
              <soapenv:Body>
                <ec:autorizacionComprobante>
                  <claveAccesoComprobante>%s</claveAccesoComprobante>
                </ec:autorizacionComprobante>
              </soapenv:Body>
            </soapenv:Envelope>
            """.formatted(NS_AUTORIZACION, claveAcceso);

        log.info("SRI autorización → {} clave_acceso={}", url, claveAcceso);
        try {
            String responseBody = postSoap(url, envelope, "");
            RespuestaAutorizacion r = parsearAutorizacion(responseBody, claveAcceso);
            cb.registrarExito();
            return r;
        } catch (SriIOException e) {
            cb.registrarFallo();
            throw e;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // HTTP transport
    // ─────────────────────────────────────────────────────────────────────

    private String postSoap(String url, String envelope, String soapAction) {
        RequestConfig cfg = RequestConfig.custom()
            .setConnectTimeout(Timeout.of(endpoints.getHttpTimeout()))
            .setResponseTimeout(Timeout.of(endpoints.getHttpTimeout()))
            .build();

        try (CloseableHttpClient http = HttpClients.custom().setDefaultRequestConfig(cfg).build()) {
            HttpPost post = new HttpPost(url);
            post.setEntity(new StringEntity(envelope, ContentType.TEXT_XML.withCharset(StandardCharsets.UTF_8)));
            post.setHeader("SOAPAction", soapAction);

            return http.execute(post, response -> {
                int sc = response.getCode();
                String body = response.getEntity() != null
                    ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)
                    : "";
                if (sc >= 500) {
                    throw new SriIOException("SRI respondió " + sc + " (probablemente saturado)");
                }
                if (sc >= 400) {
                    // 4xx puede ser SOAP Fault con detalle útil — lo devolvemos y dejamos parsearlo
                    log.warn("SRI respondió {} — body: {}", sc, body.length() > 500 ? body.substring(0, 500) + "..." : body);
                    return body;  // el parser detectará el Fault si lo hay
                }
                return body;
            });
        } catch (java.net.SocketTimeoutException | java.net.ConnectException e) {
            throw new SriIOException("SRI no responde: " + e.getMessage(), e);
        } catch (java.io.IOException e) {
            throw new SriIOException("Error de red llamando al SRI: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Parseo respuestas
    // ─────────────────────────────────────────────────────────────────────

    private RespuestaRecepcion parsearRecepcion(String soapBody, String claveAcceso) {
        Document doc = parsearXml(soapBody);
        verificarSoapFault(doc);

        String estado = textoUnico(doc, "estado");
        if (estado == null) {
            throw new SriProtocolException("Respuesta SRI sin <estado>: " + recortar(soapBody));
        }

        RespuestaRecepcion.Estado e;
        try {
            e = RespuestaRecepcion.Estado.valueOf(estado.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new SriProtocolException("Estado SRI desconocido en recepción: " + estado);
        }

        List<MensajeSri> mensajes = parsearMensajes(doc);
        log.info("SRI recepción ← clave_acceso={} estado={} mensajes={}", claveAcceso, e, mensajes.size());
        return new RespuestaRecepcion(e, claveAcceso, mensajes);
    }

    private RespuestaAutorizacion parsearAutorizacion(String soapBody, String claveAcceso) {
        Document doc = parsearXml(soapBody);
        verificarSoapFault(doc);

        String numeroComprobantes = textoUnico(doc, "numeroComprobantes");
        if (numeroComprobantes == null || "0".equals(numeroComprobantes)) {
            log.info("SRI autorización ← clave_acceso={} NO_ENCONTRADO", claveAcceso);
            return new RespuestaAutorizacion(RespuestaAutorizacion.Estado.NO_ENCONTRADO,
                claveAcceso, null, null, null, null, List.of());
        }

        Element autorizacion = primerElemento(doc, "autorizacion");
        if (autorizacion == null) {
            throw new SriProtocolException("Respuesta SRI sin <autorizacion>: " + recortar(soapBody));
        }

        String estado = textoUnico(autorizacion, "estado");
        if (estado == null) {
            throw new SriProtocolException("Respuesta SRI sin <estado> en autorización");
        }

        RespuestaAutorizacion.Estado e;
        if ("AUTORIZADO".equalsIgnoreCase(estado)) {
            e = RespuestaAutorizacion.Estado.AUTORIZADO;
        } else if ("EN_PROCESAMIENTO".equalsIgnoreCase(estado) || "EN PROCESO".equalsIgnoreCase(estado)) {
            e = RespuestaAutorizacion.Estado.EN_PROCESAMIENTO;
        } else if ("NO_AUTORIZADO".equalsIgnoreCase(estado) || "NO AUTORIZADO".equalsIgnoreCase(estado)) {
            e = RespuestaAutorizacion.Estado.NO_AUTORIZADO;
        } else {
            throw new SriProtocolException("Estado SRI desconocido en autorización: " + estado);
        }

        String numeroAutorizacion = textoUnico(autorizacion, "numeroAutorizacion");
        Instant fecha = parsearFechaSri(textoUnico(autorizacion, "fechaAutorizacion"));
        String ambiente = textoUnico(autorizacion, "ambiente");

        // El <comprobante> trae el XML autorizado COMPLETO (con la <autorización> envolviéndolo).
        // Lo guardamos tal cual viene — es lo que se conserva 7 años (RL-5).
        String xmlComprobante = textoUnico(autorizacion, "comprobante");
        byte[] xmlBytes = xmlComprobante != null
            ? xmlComprobante.getBytes(StandardCharsets.UTF_8) : null;

        List<MensajeSri> mensajes = parsearMensajes(autorizacion);
        log.info("SRI autorización ← clave_acceso={} estado={} numero_aut={}",
            claveAcceso, e, numeroAutorizacion);
        return new RespuestaAutorizacion(e, claveAcceso, numeroAutorizacion,
            fecha, ambiente, xmlBytes, mensajes);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Parsing helpers
    // ─────────────────────────────────────────────────────────────────────

    private static Document parsearXml(String body) {
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return dbf.newDocumentBuilder().parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new SriProtocolException("Respuesta SRI no es XML válido: " + e.getMessage(), e);
        }
    }

    private static void verificarSoapFault(Document doc) {
        NodeList faults = doc.getElementsByTagNameNS("http://schemas.xmlsoap.org/soap/envelope/", "Fault");
        if (faults.getLength() > 0) {
            Element fault = (Element) faults.item(0);
            String mensaje = primerHijoTexto(fault, "faultstring");
            throw new SriProtocolException("SOAP Fault del SRI: " + mensaje);
        }
    }

    private static List<MensajeSri> parsearMensajes(Object scope) {
        List<MensajeSri> out = new ArrayList<>();
        NodeList nl = (scope instanceof Document d)
            ? d.getElementsByTagName("mensaje")
            : ((Element) scope).getElementsByTagName("mensaje");
        for (int i = 0; i < nl.getLength(); i++) {
            Element e = (Element) nl.item(i);
            // Los <mensajes> a veces tienen un <mensaje> interno con campos; otras veces son hojas.
            // Acá tratamos los nodos <mensaje> que tienen hijos como entradas válidas.
            String identificador = primerHijoTexto(e, "identificador");
            String mensajeTxt = primerHijoTexto(e, "mensaje");
            String info = primerHijoTexto(e, "informacionAdicional");
            String tipo = primerHijoTexto(e, "tipo");
            if (identificador != null || mensajeTxt != null) {
                out.add(new MensajeSri(identificador, mensajeTxt, info, tipo));
            }
        }
        return out;
    }

    private static String textoUnico(Object scope, String tag) {
        NodeList nl = (scope instanceof Document d)
            ? d.getElementsByTagName(tag)
            : ((Element) scope).getElementsByTagName(tag);
        return nl.getLength() == 0 ? null : nl.item(0).getTextContent();
    }

    private static Element primerElemento(Document doc, String tag) {
        NodeList nl = doc.getElementsByTagName(tag);
        return nl.getLength() == 0 ? null : (Element) nl.item(0);
    }

    private static String primerHijoTexto(Element padre, String tag) {
        NodeList nl = padre.getElementsByTagName(tag);
        if (nl.getLength() == 0) return null;
        String t = nl.item(0).getTextContent();
        return t == null || t.isBlank() ? null : t.trim();
    }

    private static Instant parsearFechaSri(String s) {
        if (s == null || s.isBlank()) return null;
        // SRI devuelve "2026-06-20T15:30:00.000-05:00" o variantes
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ignore) {
            try {
                return java.time.OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
            } catch (DateTimeParseException ignore2) {
                log.warn("No se pudo parsear fechaAutorizacion SRI: {}", s);
                return null;
            }
        }
    }

    private static String recortar(String s) {
        if (s == null) return "null";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Excepciones
    // ─────────────────────────────────────────────────────────────────────

    /** Error de IO/red llamando al SRI. Transient — el caller puede reintentar. */
    public static class SriIOException extends RuntimeException {
        public SriIOException(String mensaje) { super(mensaje); }
        public SriIOException(String mensaje, Throwable causa) { super(mensaje, causa); }
    }

    /** Respuesta del SRI no entendible. Permanent — no reintentar; investigar. */
    public static class SriProtocolException extends RuntimeException {
        public SriProtocolException(String mensaje) { super(mensaje); }
        public SriProtocolException(String mensaje, Throwable causa) { super(mensaje, causa); }
    }

    /**
     * Lanzada cuando el circuit breaker está OPEN — el SRI tiró muchos errores recientes,
     * estamos en cooldown. El caller (JobRunr) debe tratarla como transient: el job se
     * reencola con backoff hasta que el circuito vuelva a HALF_OPEN.
     */
    public static class SriCircuitOpenException extends SriIOException {
        public SriCircuitOpenException(String mensaje) { super(mensaje); }
    }

    /**
     * Estado del circuit breaker por ambiente. Vive en RAM.
     *
     * Transiciones:
     *   CLOSED → OPEN     cuando fallosConsecutivos llega a {@link #UMBRAL_FALLOS}.
     *   OPEN → HALF_OPEN  pasado el {@link #COOLDOWN}.
     *   HALF_OPEN → CLOSED si la próxima llamada va OK.
     *   HALF_OPEN → OPEN  si la próxima llamada falla.
     */
    public static class EstadoCircuito {
        public enum Modo { CLOSED, OPEN, HALF_OPEN }

        private final AtomicReference<Modo> modo = new AtomicReference<>(Modo.CLOSED);
        private final AtomicInteger fallosConsecutivos = new AtomicInteger(0);
        private final AtomicReference<Instant> aperturaEn = new AtomicReference<>();

        public Modo getModo() {
            transicionarSiCorresponde();
            return modo.get();
        }

        public int getFallosConsecutivos() { return fallosConsecutivos.get(); }
        public Instant getAperturaEn() { return aperturaEn.get(); }

        /**
         * Verifica si la llamada puede proceder. Si el circuito está OPEN y todavía
         * estamos en cooldown, lanza {@link SriCircuitOpenException} inmediatamente.
         */
        void permitirOLanzar(String ambiente) {
            transicionarSiCorresponde();
            if (modo.get() == Modo.OPEN) {
                throw new SriCircuitOpenException(
                    "Circuito SRI " + ambiente + " ABIERTO — " + fallosConsecutivos.get()
                    + " fallos recientes. Cooldown hasta " + cooldownHasta()
                    + ". Job se reencolará automáticamente con backoff.");
            }
            // CLOSED o HALF_OPEN: dejar pasar.
        }

        void registrarExito() {
            fallosConsecutivos.set(0);
            modo.set(Modo.CLOSED);
            aperturaEn.set(null);
        }

        void registrarFallo() {
            int n = fallosConsecutivos.incrementAndGet();
            if (n >= UMBRAL_FALLOS && modo.get() != Modo.OPEN) {
                modo.set(Modo.OPEN);
                aperturaEn.set(Instant.now());
                log.warn("Circuit breaker SRI ABIERTO tras {} fallos consecutivos. "
                    + "Cooldown {} antes de half-open.", n, COOLDOWN);
            }
        }

        private void transicionarSiCorresponde() {
            if (modo.get() == Modo.OPEN) {
                Instant ts = aperturaEn.get();
                if (ts != null && Instant.now().isAfter(ts.plus(COOLDOWN))) {
                    if (modo.compareAndSet(Modo.OPEN, Modo.HALF_OPEN)) {
                        log.info("Circuit breaker SRI → HALF_OPEN, probando una request.");
                    }
                }
            }
        }

        private Instant cooldownHasta() {
            Instant a = aperturaEn.get();
            return a == null ? Instant.now() : a.plus(COOLDOWN);
        }
    }
}
