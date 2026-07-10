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
 * SMOKE TEST CONTRA SRI PRODUCCION REAL — emite factura con efecto fiscal real.
 *
 * ⚠️ ATENCIÓN: si la factura es AUTORIZADA por el SRI, tiene EFECTO FISCAL REAL.
 *    Entra al IVA del mes y debe anularse en el portal SRI manualmente.
 *
 * Diseño para minimizar riesgo:
 *   - Receptor: CONSUMIDOR FINAL (no afecta a un tercero real)
 *   - Monto: $1.00 + IVA 15% = $1.15
 *   - Secuencial: 999990+random (alto, no choca con secuenciales reales en uso)
 *   - Producto: PRUEBA-FORSETI con descripción clara
 *
 * Si SRI autoriza → confirma que el firmador funciona. Anulación manual en portal:
 *   SRI En Línea → Facturación Electrónica → Solicitudes de Anulación.
 *
 * Si SRI rechaza con error 39 (mismo de pruebas) → bug real del firmador, debuggeamos.
 * Si SRI rechaza con otro error → leemos el mensaje.
 *
 * Correr:
 *   RUN_SMOKE_SRI_PROD=true \
 *     FORSETI_P12_PATH="..." FORSETI_P12_PASSWORD="..." \
 *     mvn test -Dtest=SmokeSriProduccionIT
 */
@DisplayName("Smoke SRI PRODUCCION REAL — emite factura con efecto fiscal")
@EnabledIfEnvironmentVariable(named = "RUN_SMOKE_SRI_PROD", matches = "true",
    disabledReason = "EMITE FACTURA REAL. Activar solo cuando quieras emitir + anular manual.")
class SmokeSriProduccionIT {

    private static final String RUC_TARIUS = "1793235976001";

    @Test
    void emitir_factura_real_en_produccion_consumidor_final() throws Exception {
        String p12Path = System.getenv("FORSETI_P12_PATH");
        String p12Pwd = System.getenv("FORSETI_P12_PASSWORD");
        assertThat(p12Path).as("FORSETI_P12_PATH").isNotBlank();
        assertThat(p12Pwd).as("FORSETI_P12_PASSWORD").isNotBlank();

        byte[] p12 = Files.readAllBytes(Path.of(p12Path));
        log("✓ Cert .p12 cargado (%d bytes)", p12.length);

        // Empresa emisora — TARIUS S.A.S real (datos de la factura PDF de marzo)
        Empresa emisor = Empresa.nueva(RUC_TARIUS, "TARIUS S.A.S.");
        emisor.setDireccion("Calle: MELCHOR DE VALDEZ Interseccion: MARISCAL ANTONIO JOSE DE SUCRE");
        emisor.setRegimenTributario("RIMPE_EMPRENDEDOR");
        emisor.setObligadoContabilidad(true);  // según PDF dice SI
        emisor.setAgenteRetencion(false);

        // Secuencial alto para no chocar con secuenciales reales en uso por contadora.
        // El secuencial real en marzo fue 000000002; usamos 999990+ para estar muy por arriba.
        long secuencial = 999_990L + (long) (Math.random() * 9);
        String establecimiento = "001";
        String puntoEmision = "001";  // distinto del 205 que usa la contadora
        LocalDate hoy = LocalDate.now();
        String claveAcceso = ClaveAccesoGenerator.generar(
            hoy, ClaveAccesoGenerator.TipoDocumento.FACTURA, RUC_TARIUS,
            ClaveAccesoGenerator.Ambiente.PRODUCCION,
            establecimiento, puntoEmision, secuencial);
        String numeroComprobante = "%s-%s-%09d".formatted(establecimiento, puntoEmision, secuencial);

        log("⚠ FACTURA REAL EN PRODUCCION: %s clave=%s", numeroComprobante, claveAcceso);

        Comprobante c = Comprobante.nuevo(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "FACTURA", "PRODUCCION", secuencial, numeroComprobante, claveAcceso, hoy);
        // Consumidor Final — no afecta a un tercero real
        c.receptor("07", "9999999999999", "CONSUMIDOR FINAL",
                   null, null, null);
        c.formaPago("01", 0);   // Efectivo, plazo 0
        c.totales(new BigDecimal("1.00"), BigDecimal.ZERO,
                  new BigDecimal("0.15"), new BigDecimal("1.15"));

        ComprobanteDetalle item = ComprobanteDetalle.nuevo(
            UUID.randomUUID(), UUID.randomUUID(), 1,
            "PRUEBA-FORSETI", "PRUEBA TECNICA FORSETI — facturacion electronica integracion",
            new BigDecimal("1.000000"), new BigDecimal("1.000000"),
            BigDecimal.ZERO, new BigDecimal("1.00"),
            CodigoPorcentajeIva.IVA_15,
            new BigDecimal("1.00"), new BigDecimal("0.15"));

        // Generar XML + validar XSD oficial
        Document doc = FacturaXmlBuilder.construir(emisor, c, List.of(item));
        XsdValidator xsd = new XsdValidator();
        xsd.validarFactura(doc);
        log("✓ XML valida XSD oficial SRI v2.1.0");

        // Firmar XAdES-BES
        XadesSigner signer = new XadesSigner();
        signer.firmar(doc, p12, p12Pwd);
        byte[] xmlFirmado = FacturaXmlBuilder.serializar(doc);
        log("✓ XML firmado (%d bytes)", xmlFirmado.length);
        Path outFile = Path.of(System.getProperty("java.io.tmpdir"), "forseti-smoke-prod-firmado.xml");
        Files.write(outFile, xmlFirmado);
        log("   XML firmado guardado en: %s", outFile);

        // SRI PRODUCCION endpoints
        SriEndpoints ep = new SriEndpoints();
        SriEndpoints.Endpoints prod = new SriEndpoints.Endpoints();
        prod.setRecepcion("https://cel.sri.gob.ec/comprobantes-electronicos-ws/RecepcionComprobantesOffline");
        prod.setAutorizacion("https://cel.sri.gob.ec/comprobantes-electronicos-ws/AutorizacionComprobantesOffline");
        ep.setProduccion(prod);
        ep.setHttpTimeout(Duration.ofSeconds(60));
        SriSoapClient sri = new SriSoapClient(ep);

        log("→ Enviando a SRI PRODUCCION recepcion…");
        RespuestaRecepcion rec = sri.recepcion(xmlFirmado, "PRODUCCION", claveAcceso);
        log("← Recepcion: %s", rec.estado());
        for (var m : rec.mensajes()) log("   %s", m.resumen());

        if (rec.fueDevuelta()) {
            throw new AssertionError("SRI PROD DEVOLVIO en recepcion. Mensajes: "
                + rec.mensajes().stream().map(x -> x.resumen()).toList());
        }

        // Polling autorización (hasta 60s)
        log("→ Polling autorizacion en PROD…");
        RespuestaAutorizacion aut = null;
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5_000);
            aut = sri.autorizacion(claveAcceso, "PRODUCCION");
            log("   ← %s", aut.estado());
            if (aut.fueAutorizado() || aut.fueRechazado()) break;
        }

        if (aut == null) throw new AssertionError("No se obtuvo respuesta de autorizacion");
        if (aut.fueRechazado()) {
            for (var m : aut.mensajes()) log("   %s", m.resumen());
            throw new AssertionError("SRI PROD NO_AUTORIZADO. Mensajes: "
                + aut.mensajes().stream().map(x -> x.resumen()).toList());
        }

        // ✅ AUTORIZADO en PRODUCCION
        assertThat(aut.fueAutorizado())
            .as("Factura debe quedar AUTORIZADA en SRI produccion")
            .isTrue();
        log("");
        log("✅✅✅ AUTORIZADO EN PRODUCCION ✅✅✅");
        log("   clave_acceso: %s", claveAcceso);
        log("   numero: %s", numeroComprobante);
        log("   monto: $1.15 (Consumidor Final)");
        log("   numero_autorizacion: %s", aut.numeroAutorizacion());
        log("   fecha_autorizacion: %s", aut.fechaAutorizacion());
        log("");
        log("⚠ FACTURA REAL EMITIDA — anular en https://srienlinea.sri.gob.ec");
        log("   SRI En Linea → Facturacion Electronica → Solicitudes de Anulacion");
        log("   Motivo sugerido: 'Prueba tecnica de integracion del sistema'");
    }

    private static void log(String fmt, Object... args) {
        System.out.println("[SMOKE PROD] " + String.format(fmt, args));
    }
}
