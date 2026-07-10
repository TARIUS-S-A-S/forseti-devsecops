package ec.tarius.forseti.compras.service;

import ec.tarius.forseti.auth.UsuarioActual;
import ec.tarius.forseti.compras.domain.Compra;
import ec.tarius.forseti.compras.domain.IngresoManual;
import ec.tarius.forseti.compras.dto.ComprasDtos.FlujoCajaResponse;
import ec.tarius.forseti.compras.repo.CompraRepository;
import ec.tarius.forseti.compras.repo.IngresoManualRepository;
import ec.tarius.forseti.emision.ComprobanteRepository;
import ec.tarius.forseti.emision.domain.Comprobante;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Reportes financieros: flujo de caja (HU-F4) + exports CSV (HU-F5).
 *
 * Reglas:
 *   - Movimientos {@code anulada=true} se EXCLUYEN de totales y de los exports (RNF-6).
 *   - Comprobantes SRI: solo cuentan los AUTORIZADA. Los ABANDONADA/NO_AUTORIZADA quedan
 *     fuera. Las NC AUTORIZADA RESTAN al total (anulan parcial o totalmente la venta).
 *   - El CSV usa separador ";" + BOM UTF-8 al inicio para que Excel ES-EC lo abra sin
 *     romper acentos ni dividir mal las columnas (gate ②). Números siempre con 2 decimales.
 *   - Todas las queries filtran por {@code empresaId} explícitamente (defensa en profundidad
 *     sobre RLS — el bug histórico del @Order del aspect AOP podría romper RLS sin avisar).
 *   - Una sola lectura por tabla del rango — antes había double-read en flujoCaja.
 */
@Service
public class ReportesService {

    private static final DateTimeFormatter F = DateTimeFormatter.ISO_LOCAL_DATE;

    private final CompraRepository compraRepo;
    private final IngresoManualRepository ingresoRepo;
    private final ComprobanteRepository comprobanteRepo;

    public ReportesService(CompraRepository compraRepo,
                           IngresoManualRepository ingresoRepo,
                           ComprobanteRepository comprobanteRepo) {
        this.compraRepo = compraRepo;
        this.ingresoRepo = ingresoRepo;
        this.comprobanteRepo = comprobanteRepo;
    }

    @Transactional(readOnly = true)
    public FlujoCajaResponse flujoCaja(LocalDate desde, LocalDate hasta) {
        UUID empresaId = UsuarioActual.empresaActivaObligatoria();
        LocalDate d = desde != null ? desde : LocalDate.now().withDayOfMonth(1);
        LocalDate h = hasta != null ? hasta : LocalDate.now();

        // 1 sola lectura de compras del período, filtrar activas en memoria
        List<Compra> comprasActivas = compraRepo
            .findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(empresaId, d, h)
            .stream().filter(c -> !c.isAnulada()).toList();
        BigDecimal egresosPagados = comprasActivas.stream()
            .filter(c -> c.getEstadoPago() == Compra.EstadoPago.PAGADO)
            .map(Compra::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal egresosPendientes = comprasActivas.stream()
            .filter(c -> c.getEstadoPago() != Compra.EstadoPago.PAGADO)
            .map(Compra::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);

        // 1 sola lectura de ingresos manuales
        List<IngresoManual> ingresosActivos = ingresoRepo
            .findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(empresaId, d, h)
            .stream().filter(i -> !i.isAnulada()).toList();
        BigDecimal ingresosCobradosManual = ingresosActivos.stream()
            .filter(i -> i.getEstadoCobro() == IngresoManual.EstadoCobro.COBRADO)
            .map(IngresoManual::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ingresosPendientesManual = ingresosActivos.stream()
            .filter(i -> i.getEstadoCobro() != IngresoManual.EstadoCobro.COBRADO)
            .map(IngresoManual::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);

        // 1 sola lectura de comprobantes SRI — facturas suman, NC restan, resto se ignora.
        // (Comprobante no tiene flag "anulada" — los ABANDONADA/NO_AUTORIZADA ya quedan
        //  fuera por el filtro de estado AUTORIZADA.)
        List<Comprobante> comprobantesAutorizados = comprobanteRepo
            .findByEmpresaIdAndFechaEmisionBetween(empresaId, d, h)
            .stream().filter(c -> c.getEstado() == Comprobante.Estado.AUTORIZADA).toList();
        BigDecimal facturasAutorizadas = comprobantesAutorizados.stream()
            .filter(c -> "FACTURA".equals(c.getTipoComprobante()))
            .map(Comprobante::getImporteTotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ncAutorizadas = comprobantesAutorizados.stream()
            .filter(c -> "NOTA_CREDITO".equals(c.getTipoComprobante()))
            .map(Comprobante::getImporteTotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalIngresosCobrados = ingresosCobradosManual
            .add(facturasAutorizadas).subtract(ncAutorizadas);

        BigDecimal saldo = totalIngresosCobrados.subtract(egresosPagados);

        return new FlujoCajaResponse(d, h,
            scale(totalIngresosCobrados), scale(ingresosPendientesManual),
            scale(egresosPagados), scale(egresosPendientes), scale(saldo));
    }

    /**
     * Export CSV de compras del período. Formato: ";" como separador + BOM UTF-8.
     * Excluye anuladas. Gate ② del Sprint 5.
     */
    @Transactional(readOnly = true)
    public byte[] exportCsvCompras(LocalDate desde, LocalDate hasta) {
        UUID empresaId = UsuarioActual.empresaActivaObligatoria();
        LocalDate d = desde != null ? desde : LocalDate.now().withDayOfMonth(1);
        LocalDate h = hasta != null ? hasta : LocalDate.now();

        List<Compra> compras = compraRepo
            .findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(empresaId, d, h)
            .stream().filter(c -> !c.isAnulada()).toList();

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(0xEF); bos.write(0xBB); bos.write(0xBF);

        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(bos, StandardCharsets.UTF_8))) {
            w.println("Fecha;Tipo;Numero;Proveedor RUC;Proveedor Razon Social;Concepto;"
                + "Base 15%;Base 0%;Base No Objeto;Base Exento;IVA 15%;Retencion IR;Retencion IVA;Total;"
                + "Deducible;Estado pago;Fecha pago;Origen");
            for (Compra c : compras) {
                w.println(String.join(";",
                    F.format(c.getFechaEmision()),
                    csv(c.getTipoDocumento().name()),
                    csv(c.getNumeroDocumento()),
                    csv(c.getProveedorIdentificacion()),
                    csv(c.getProveedorRazonSocial()),
                    csv(c.getConcepto()),
                    money(c.getBaseIva15()), money(c.getBaseIva0()),
                    money(c.getBaseNoObjeto()), money(c.getBaseExento()),
                    money(c.getValorIva15()),
                    money(c.getRetencionIr()), money(c.getRetencionIva()),
                    money(c.getTotal()),
                    c.isDeducible() ? "SI" : "NO",
                    c.getEstadoPago().name(),
                    c.getFechaPago() != null ? F.format(c.getFechaPago()) : "",
                    c.getOrigen().name()));
            }
            w.flush();
            if (w.checkError()) {
                throw new IllegalStateException("Error escribiendo CSV de compras");
            }
        }
        return bos.toByteArray();
    }

    /** Export CSV de ventas (facturas SRI autorizadas + NC + ingresos manuales). */
    @Transactional(readOnly = true)
    public byte[] exportCsvVentas(LocalDate desde, LocalDate hasta) {
        UUID empresaId = UsuarioActual.empresaActivaObligatoria();
        LocalDate d = desde != null ? desde : LocalDate.now().withDayOfMonth(1);
        LocalDate h = hasta != null ? hasta : LocalDate.now();

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(0xEF); bos.write(0xBB); bos.write(0xBF);

        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(bos, StandardCharsets.UTF_8))) {
            w.println("Fecha;Tipo;Numero;Cliente Id;Cliente Razon Social;"
                + "Base 15%;Base 0%;IVA 15%;Retencion Recibida;Total;Estado;Origen");
            // Facturas/NC SRI autorizadas — 1 sola lectura filtrada por empresa
            List<Comprobante> comps = comprobanteRepo
                .findByEmpresaIdAndFechaEmisionBetween(empresaId, d, h)
                .stream().filter(c -> c.getEstado() == Comprobante.Estado.AUTORIZADA).toList();
            for (Comprobante c : comps) {
                w.println(String.join(";",
                    F.format(c.getFechaEmision()),
                    csv(c.getTipoComprobante()),
                    csv(c.getNumeroComprobante()),
                    csv(c.getReceptorIdentificacion()),
                    csv(c.getReceptorRazonSocial()),
                    "", "",  // bases no desglosadas en Comprobante (solo totales) — Sprint 7
                    money(c.getTotalIva()),
                    "",
                    money(c.getImporteTotal()),
                    c.getEstado().name(),
                    "SRI"));
            }
            // Ingresos manuales activos
            List<IngresoManual> manuales = ingresoRepo
                .findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(empresaId, d, h)
                .stream().filter(i -> !i.isAnulada()).toList();
            for (IngresoManual i : manuales) {
                w.println(String.join(";",
                    F.format(i.getFechaEmision()),
                    "INGRESO_MANUAL",
                    "—",
                    csv(i.getClienteIdentificacion()),
                    csv(i.getClienteRazonSocial()),
                    money(i.getBaseIva15()),
                    money(i.getBaseIva0()),
                    money(i.getValorIva15()),
                    money(i.getRetencionRecibida()),
                    money(i.getTotal()),
                    i.getEstadoCobro().name(),
                    "MANUAL"));
            }
            w.flush();
            if (w.checkError()) {
                throw new IllegalStateException("Error escribiendo CSV de ventas");
            }
        }
        return bos.toByteArray();
    }

    /** Escapa un valor CSV: si tiene ";", "\"" o newline, lo entrecomilla y duplica las comillas. */
    private static String csv(String v) {
        if (v == null) return "";
        if (v.contains(";") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    /**
     * Formato monetario consistente para CSV: 2 decimales con PUNTO decimal.
     *
     * Decisión: usar punto decimal (no coma) para que el CSV sea locale-neutro.
     * Excel ES-EC interpreta el punto como separador de miles por default — para que lo
     * lea como decimal, el usuario hace "Datos → Texto en columnas → Avanzadas → Separador
     * decimal = punto". Es 1 paso extra pero predecible y consistente entre exports.
     *
     * Alternativa rechazada: usar coma decimal y forzar locale ES-EC. Rompería para usuarios
     * con Excel en inglés u otros locales (común en contadoras que trabajan con clientes
     * internacionales).
     *
     * Si en el futuro queremos mejor experiencia "1 click abre en Excel ES sin pasos extra",
     * la solución es exportar a .xlsx con framework dedicado (Apache POI), no cambiar este
     * helper.
     */
    private static String money(BigDecimal v) {
        return scale(v).toPlainString();
    }

    private static BigDecimal scale(BigDecimal v) {
        if (v == null) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
