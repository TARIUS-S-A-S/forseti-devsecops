package ec.tarius.forseti.emision;

import ec.tarius.forseti.emision.domain.Comprobante;
import ec.tarius.forseti.emision.domain.ComprobanteDetalle;
import ec.tarius.forseti.emision.domain.ComprobanteDetalle.CodigoPorcentajeIva;
import ec.tarius.forseti.emision.sri.ClaveAccesoGenerator;
import ec.tarius.forseti.emision.sri.FacturaXmlBuilder;
import ec.tarius.forseti.emision.sri.RespuestaAutorizacion;
import ec.tarius.forseti.emision.sri.RespuestaRecepcion;
import ec.tarius.forseti.emision.sri.SriEndpoints;
import ec.tarius.forseti.emision.sri.SriSoapClient;
import ec.tarius.forseti.emision.sri.XadesSigner;
import ec.tarius.forseti.emision.sri.XsdValidator;
import ec.tarius.forseti.empresa.domain.Empresa;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.w3c.dom.Document;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GATE Sprint 3 ①: factura AUTORIZADA en el ambiente PRUEBAS REAL del SRI.
 *
 * NO se ejecuta en CI ni en mvn verify por default — requiere:
 *   - .p12 real de TARIUS en disco
 *   - Password del .p12 vía env var
 *   - Conectividad a celcer.sri.gob.ec
 *
 * Correr:
 *   RUN_SMOKE_SRI=true \
 *     FORSETI_P12_PATH="/path/al/cert.p12" \
 *     FORSETI_P12_PASSWORD="..." \
 *     mvn test -Dtest=SmokeSriPruebasIT
 *
 * Qué hace:
 *   1. Carga el .p12 desde disco
 *   2. Genera una factura sintética: TARIUS S.A.S emite a TARIUS S.A.S (RUC TARIUS),
 *      1 ítem de "Prueba Forseti" por $1.00 + IVA 15%, fecha = hoy, secuencial 1
 *   3. Valida XSD oficial
 *   4. Firma XAdES-BES con el .p12 real
 *   5. POST al WS recepción de celcer.sri.gob.ec
 *   6. Espera 5s y consulta autorización
 *   7. Polea hasta AUTORIZADO / NO_AUTORIZADO / timeout 60s
 *   8. Imprime claveAcceso, estado, mensajes SRI
 *
 * Si SRI responde AUTORIZADO → gate ① cumplido.
 * Si rechaza → leer mensaje y ajustar (probablemente algún detalle del XML).
 */
@DisplayName("Smoke SRI Pruebas REAL (gate Sprint 3 ①)")
@EnabledIfEnvironmentVariable(named = "RUN_SMOKE_SRI", matches = "true",
    disabledReason = "Requiere .p12 real + password. Ver header del test.")
class SmokeSriPruebasIT {

    private static final String RUC_TARIUS = "1793235976001";

    @Test
    void emitir_factura_real_y_obtener_autorizacion_del_sri_pruebas() throws Exception {
        // 1. Cargar .p12
        String p12Path = System.getenv("FORSETI_P12_PATH");
        String p12Pwd = System.getenv("FORSETI_P12_PASSWORD");
        assertThat(p12Path).as("FORSETI_P12_PATH").isNotBlank();
        assertThat(p12Pwd).as("FORSETI_P12_PASSWORD").isNotBlank();

        byte[] p12 = Files.readAllBytes(Path.of(p12Path));
        log("✓ Cert .p12 cargado (%d bytes)", p12.length);

        // 2. Datos sintéticos: factura mínima TARIUS → TARIUS (autoventa para test)
        Empresa emisor = Empresa.nueva(RUC_TARIUS, "TARIUS S.A.S.");
        emisor.setDireccion("Av. Amazonas N34-451 y Av. Atahualpa, Quito");
        emisor.setRegimenTributario("RIMPE_EMPRENDEDOR");
        emisor.setObligadoContabilidad(false);
        emisor.setAgenteRetencion(false);

        // Punto 205 — el que TARIUS tiene autorizado según factura real del Facturador SRI.
        // Secuencial alto (999000+) para no chocar con secuenciales reales si por algún motivo
        // SRI los reusa entre ambientes.
        long secuencial = 999_000L + (long) (Math.random() * 999);
        String establecimiento = "001";
        String puntoEmision = "205";
        LocalDate hoy = LocalDate.now();
        String claveAcceso = ClaveAccesoGenerator.generar(
            hoy, ClaveAccesoGenerator.TipoDocumento.FACTURA, RUC_TARIUS,
            ClaveAccesoGenerator.Ambiente.PRUEBAS, establecimiento, puntoEmision, secuencial);

        String numeroComprobante = "%s-%s-%09d".formatted(establecimiento, puntoEmision, secuencial);
        Comprobante c = Comprobante.nuevo(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "FACTURA", "PRUEBAS", secuencial, numeroComprobante,
            claveAcceso, hoy);
        c.receptor("04", RUC_TARIUS, "TARIUS S.A.S.",
                   "Av. Amazonas N34-451, Quito", null, null);
        c.formaPago("01", 0);
        c.totales(new BigDecimal("1.00"), BigDecimal.ZERO,
                  new BigDecimal("0.15"), new BigDecimal("1.15"));

        ComprobanteDetalle item = ComprobanteDetalle.nuevo(
            UUID.randomUUID(), UUID.randomUUID(), 1,
            "PRUEBA-FORSETI", "Prueba Forseti emision SRI",
            new BigDecimal("1.000000"), new BigDecimal("1.000000"),
            BigDecimal.ZERO, new BigDecimal("1.00"),
            CodigoPorcentajeIva.IVA_15,
            new BigDecimal("1.00"), new BigDecimal("0.15"));

        log("✓ Comprobante sintético: clave_acceso=%s", claveAcceso);

        // 3. Generar XML + validar XSD oficial
        Document doc = FacturaXmlBuilder.construir(emisor, c, List.of(item));
        XsdValidator xsd = new XsdValidator();
        xsd.validarFactura(doc);
        log("✓ XML valida XSD oficial SRI v2.1.0");

        // 4. Firmar XAdES-BES con .p12 real
        XadesSigner signer = new XadesSigner();
        signer.firmar(doc, p12, p12Pwd);
        byte[] xmlFirmado = FacturaXmlBuilder.serializar(doc);
        log("✓ XML firmado XAdES-BES (%d bytes)", xmlFirmado.length);
        // Para debugging: guardar el XML firmado a disco
        Path outFile = Path.of(System.getProperty("java.io.tmpdir"), "forseti-smoke-firmado.xml");
        Files.write(outFile, xmlFirmado);
        log("   XML firmado guardado en: %s", outFile);

        // 5. POST a recepción SRI pruebas REAL
        SriEndpoints ep = new SriEndpoints();
        SriEndpoints.Endpoints pruebas = new SriEndpoints.Endpoints();
        pruebas.setRecepcion("https://celcer.sri.gob.ec/comprobantes-electronicos-ws/RecepcionComprobantesOffline");
        pruebas.setAutorizacion("https://celcer.sri.gob.ec/comprobantes-electronicos-ws/AutorizacionComprobantesOffline");
        ep.setPruebas(pruebas);
        ep.setHttpTimeout(Duration.ofSeconds(60));
        SriSoapClient sri = new SriSoapClient(ep);

        log("→ Enviando a SRI recepción (esto puede tomar varios segundos)...");
        RespuestaRecepcion rec = sri.recepcion(xmlFirmado, "PRUEBAS", claveAcceso);
        log("← Recepción: %s", rec.estado());
        for (var m : rec.mensajes()) {
            log("   %s", m.resumen());
        }

        if (rec.fueDevuelta()) {
            throw new AssertionError("SRI DEVOLVIÓ el comprobante. Mensajes: "
                + rec.mensajes().stream().map(x -> x.resumen()).toList());
        }
        assertThat(rec.fueRecibida()).as("SRI debe recibir el comprobante").isTrue();

        // 6. Polling de autorización (hasta 60s, cada 5s)
        log("→ Polling autorización…");
        RespuestaAutorizacion aut = null;
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5_000);
            aut = sri.autorizacion(claveAcceso, "PRUEBAS");
            log("   ← %s", aut.estado());
            if (aut.fueAutorizado() || aut.fueRechazado()) break;
        }

        if (aut == null) throw new AssertionError("No se obtuvo respuesta de autorización");
        if (aut.fueRechazado()) {
            for (var m : aut.mensajes()) log("   %s", m.resumen());
            throw new AssertionError("SRI NO_AUTORIZADO. Mensajes: "
                + aut.mensajes().stream().map(x -> x.resumen()).toList());
        }

        // 7. ✅ AUTORIZADO
        assertThat(aut.fueAutorizado())
            .as("Gate Sprint 3 ①: factura debe quedar AUTORIZADA en SRI pruebas")
            .isTrue();
        log("✅ AUTORIZADO");
        log("   numero_autorizacion: %s", aut.numeroAutorizacion());
        log("   fecha_autorizacion: %s", aut.fechaAutorizacion());
        log("   xml_autorizado: %d bytes", aut.xmlAutorizado() != null ? aut.xmlAutorizado().length : 0);
    }

    private static void log(String fmt, Object... args) {
        System.out.println("[SMOKE SRI] " + String.format(fmt, args));
    }
}
