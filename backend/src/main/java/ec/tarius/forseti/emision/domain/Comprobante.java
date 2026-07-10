package ec.tarius.forseti.emision.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Cabecera de un comprobante electrónico SRI (factura, NC, ND, retención…).
 * Su ciclo de vida es la máquina de estados de Sprint 3 HU-F9:
 *
 *   BORRADOR → FIRMADA → ENVIADA → EN_PROCESO → AUTORIZADA
 *                              ↘  DEVUELTA / NO_AUTORIZADA / ABANDONADA
 *
 * Transiciones legales en {@link Estado#puedeTransicionarA(Estado)}.
 */
@Entity
@Table(name = "comprobante")
public class Comprobante {

    public enum Estado {
        BORRADOR, FIRMADA, ENVIADA, EN_PROCESO,
        AUTORIZADA, DEVUELTA, NO_AUTORIZADA, ABANDONADA;

        public boolean puedeTransicionarA(Estado nuevo) {
            return switch (this) {
                case BORRADOR -> nuevo == FIRMADA || nuevo == ABANDONADA;
                case FIRMADA -> nuevo == ENVIADA || nuevo == DEVUELTA || nuevo == ABANDONADA;
                case ENVIADA -> nuevo == EN_PROCESO || nuevo == AUTORIZADA
                            || nuevo == DEVUELTA || nuevo == NO_AUTORIZADA
                            || nuevo == ABANDONADA;
                case EN_PROCESO -> nuevo == AUTORIZADA || nuevo == DEVUELTA
                                || nuevo == NO_AUTORIZADA || nuevo == ABANDONADA;
                // Terminales
                case AUTORIZADA, NO_AUTORIZADA, ABANDONADA -> false;
                // DEVUELTA permite reenviar tras corregir → vuelve a FIRMADA
                case DEVUELTA -> nuevo == FIRMADA || nuevo == ABANDONADA;
            };
        }

        public boolean esTerminal() {
            return this == AUTORIZADA || this == NO_AUTORIZADA || this == ABANDONADA;
        }
    }

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Column(name = "establecimiento_id", nullable = false)
    private UUID establecimientoId;

    @Column(name = "punto_emision_id", nullable = false)
    private UUID puntoEmisionId;

    @Column(name = "secuencial_id", nullable = false)
    private UUID secuencialId;

    @Column(name = "tipo_comprobante", nullable = false, length = 30)
    private String tipoComprobante;

    @Column(name = "ambiente", nullable = false, length = 15)
    private String ambiente;

    @Column(name = "tipo_emision", nullable = false, length = 10)
    private String tipoEmision = "NORMAL";

    @Column(name = "secuencial_numero", nullable = false)
    private long secuencialNumero;

    @Column(name = "numero_comprobante", nullable = false, length = 17)
    private String numeroComprobante;

    @Column(name = "clave_acceso", nullable = false, length = 49, unique = true)
    private String claveAcceso;

    @Column(name = "fecha_emision", nullable = false)
    private LocalDate fechaEmision;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 20)
    private Estado estado = Estado.BORRADOR;

    @Column(name = "receptor_tipo_id", nullable = false, length = 2)
    private String receptorTipoId;

    @Column(name = "receptor_identificacion", nullable = false, length = 20)
    private String receptorIdentificacion;

    @Column(name = "receptor_razon_social", nullable = false, length = 300)
    private String receptorRazonSocial;

    @Column(name = "receptor_direccion", columnDefinition = "text")
    private String receptorDireccion;

    @Column(name = "receptor_email", columnDefinition = "text")
    private String receptorEmail;

    @Column(name = "receptor_telefono", length = 30)
    private String receptorTelefono;

    @Column(name = "subtotal_sin_impuestos", nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotalSinImpuestos = BigDecimal.ZERO;

    @Column(name = "total_descuento", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalDescuento = BigDecimal.ZERO;

    @Column(name = "total_iva", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalIva = BigDecimal.ZERO;

    @Column(name = "importe_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal importeTotal = BigDecimal.ZERO;

    @Column(name = "moneda", nullable = false, length = 10)
    private String moneda = "DOLAR";

    @Column(name = "forma_pago", nullable = false, length = 2)
    private String formaPago = "01";

    @Column(name = "plazo_dias", nullable = false)
    private int plazoDias = 0;

    @Column(name = "xml_firmado", columnDefinition = "bytea")
    private byte[] xmlFirmado;

    /** Cert que firmó este comprobante. NULL si pre-V14 o BORRADOR (sin firmar aún). */
    @Column(name = "certificado_id")
    private UUID certificadoId;

    /** Cuándo se envió el correo al receptor con XML+RIDE. NULL si no se envió aún. */
    @Column(name = "correo_enviado_at")
    private Instant correoEnviadoAt;

    // Sprint 4 — campos NC (NULL cuando el comprobante no es NOTA_CREDITO)
    @Column(name = "doc_modificado_tipo")
    private String docModificadoTipo;
    @Column(name = "doc_modificado_numero")
    private String docModificadoNumero;
    @Column(name = "doc_modificado_fecha")
    private LocalDate docModificadoFecha;
    @Column(name = "motivo", columnDefinition = "text")
    private String motivo;

    @Column(name = "xml_autorizado", columnDefinition = "bytea")
    private byte[] xmlAutorizado;

    @Column(name = "numero_autorizacion", length = 49)
    private String numeroAutorizacion;

    @Column(name = "fecha_autorizacion")
    private Instant fechaAutorizacion;

    @Column(name = "mensaje_sri", columnDefinition = "text")
    private String mensajeSri;

    @Column(name = "codigo_error_sri", length = 20)
    private String codigoErrorSri;

    @Column(name = "intentos_envio", nullable = false)
    private int intentosEnvio = 0;

    @Column(name = "ultimo_intento_at")
    private Instant ultimoIntentoAt;

    @Column(name = "siguiente_intento_at")
    private Instant siguienteIntentoAt;

    @Column(name = "creado_por_usuario_id")
    private UUID creadoPorUsuarioId;

    @Column(name = "creado_at", nullable = false, updatable = false)
    private Instant creadoAt = Instant.now();

    @Column(name = "actualizado_at", nullable = false)
    private Instant actualizadoAt = Instant.now();

    protected Comprobante() {}

    public static Comprobante nuevo(UUID empresaId, UUID establecimientoId, UUID puntoEmisionId,
                                     UUID secuencialId, String tipoComprobante, String ambiente,
                                     long secuencialNumero, String numeroComprobante,
                                     String claveAcceso, LocalDate fechaEmision) {
        Comprobante c = new Comprobante();
        c.empresaId = empresaId;
        c.establecimientoId = establecimientoId;
        c.puntoEmisionId = puntoEmisionId;
        c.secuencialId = secuencialId;
        c.tipoComprobante = tipoComprobante;
        c.ambiente = ambiente;
        c.secuencialNumero = secuencialNumero;
        c.numeroComprobante = numeroComprobante;
        c.claveAcceso = claveAcceso;
        c.fechaEmision = fechaEmision;
        return c;
    }

    public void receptor(String tipoId, String identificacion, String razonSocial,
                          String direccion, String email, String telefono) {
        this.receptorTipoId = tipoId;
        this.receptorIdentificacion = identificacion;
        this.receptorRazonSocial = razonSocial;
        this.receptorDireccion = direccion;
        this.receptorEmail = email;
        this.receptorTelefono = telefono;
    }

    public void totales(BigDecimal subtotalSinImpuestos, BigDecimal totalDescuento,
                         BigDecimal totalIva, BigDecimal importeTotal) {
        this.subtotalSinImpuestos = subtotalSinImpuestos;
        this.totalDescuento = totalDescuento;
        this.totalIva = totalIva;
        this.importeTotal = importeTotal;
    }

    public void formaPago(String codigo, int plazoDias) {
        this.formaPago = codigo;
        this.plazoDias = plazoDias;
    }

    /** Transiciona el estado validando que sea legal. Lanza si no lo es. */
    public void cambiarEstado(Estado nuevo) {
        if (!estado.puedeTransicionarA(nuevo)) {
            throw new IllegalStateException(
                "Transición ilegal: " + estado + " → " + nuevo);
        }
        this.estado = nuevo;
        this.actualizadoAt = Instant.now();
    }

    public void marcarFirmado(byte[] xmlFirmado, UUID certificadoId) {
        this.xmlFirmado = xmlFirmado;
        this.certificadoId = certificadoId;
        cambiarEstado(Estado.FIRMADA);
    }

    /** Sobrecarga back-compat por si algún test viejo invoca sin certId. */
    public void marcarFirmado(byte[] xmlFirmado) {
        marcarFirmado(xmlFirmado, null);
    }

    public UUID getCertificadoId() { return certificadoId; }
    public Instant getCorreoEnviadoAt() { return correoEnviadoAt; }
    public void marcarCorreoEnviado() { this.correoEnviadoAt = Instant.now(); }

    // Sprint 4 — campos NC
    public String getDocModificadoTipo() { return docModificadoTipo; }
    public String getDocModificadoNumero() { return docModificadoNumero; }
    public LocalDate getDocModificadoFecha() { return docModificadoFecha; }
    public String getMotivo() { return motivo; }

    public void notaCreditoSobre(String tipoDoc, String numeroDoc, LocalDate fechaDoc, String motivo) {
        this.docModificadoTipo = tipoDoc;
        this.docModificadoNumero = numeroDoc;
        this.docModificadoFecha = fechaDoc;
        this.motivo = motivo;
    }

    public void marcarEnviado() {
        this.intentosEnvio++;
        this.ultimoIntentoAt = Instant.now();
        cambiarEstado(Estado.ENVIADA);
    }

    public void marcarAutorizado(byte[] xmlAutorizado, String numeroAutorizacion, Instant fecha) {
        this.xmlAutorizado = xmlAutorizado;
        this.numeroAutorizacion = numeroAutorizacion;
        this.fechaAutorizacion = fecha;
        this.mensajeSri = "Autorizado por el SRI";
        this.codigoErrorSri = null;
        this.siguienteIntentoAt = null;
        cambiarEstado(Estado.AUTORIZADA);
    }

    public void marcarDevuelto(String codigoError, String mensaje) {
        this.codigoErrorSri = codigoError;
        this.mensajeSri = mensaje;
        cambiarEstado(Estado.DEVUELTA);
    }

    public void marcarNoAutorizado(String codigoError, String mensaje) {
        this.codigoErrorSri = codigoError;
        this.mensajeSri = mensaje;
        this.siguienteIntentoAt = null;
        cambiarEstado(Estado.NO_AUTORIZADA);
    }

    public void marcarEnProceso() {
        cambiarEstado(Estado.EN_PROCESO);
    }

    public void programarSiguienteIntento(Instant cuando) {
        this.siguienteIntentoAt = cuando;
    }

    /**
     * Registra un intento fallido SIN transicionar de estado. Para errores transient (IO)
     * donde queremos reintentar más tarde manteniendo FIRMADA/ENVIADA.
     */
    public void registrarIntentoFallido(Instant cuando) {
        this.intentosEnvio++;
        this.ultimoIntentoAt = Instant.now();
        this.siguienteIntentoAt = cuando;
        this.actualizadoAt = Instant.now();
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getEmpresaId() { return empresaId; }
    public UUID getEstablecimientoId() { return establecimientoId; }
    public UUID getPuntoEmisionId() { return puntoEmisionId; }
    public UUID getSecuencialId() { return secuencialId; }
    public String getTipoComprobante() { return tipoComprobante; }
    public String getAmbiente() { return ambiente; }
    public String getTipoEmision() { return tipoEmision; }
    public long getSecuencialNumero() { return secuencialNumero; }
    public String getNumeroComprobante() { return numeroComprobante; }
    public String getClaveAcceso() { return claveAcceso; }
    public LocalDate getFechaEmision() { return fechaEmision; }
    public Estado getEstado() { return estado; }
    public String getReceptorTipoId() { return receptorTipoId; }
    public String getReceptorIdentificacion() { return receptorIdentificacion; }
    public String getReceptorRazonSocial() { return receptorRazonSocial; }
    public String getReceptorDireccion() { return receptorDireccion; }
    public String getReceptorEmail() { return receptorEmail; }
    public String getReceptorTelefono() { return receptorTelefono; }
    public BigDecimal getSubtotalSinImpuestos() { return subtotalSinImpuestos; }
    public BigDecimal getTotalDescuento() { return totalDescuento; }
    public BigDecimal getTotalIva() { return totalIva; }
    public BigDecimal getImporteTotal() { return importeTotal; }
    public String getMoneda() { return moneda; }
    public String getFormaPago() { return formaPago; }
    public int getPlazoDias() { return plazoDias; }
    public byte[] getXmlFirmado() { return xmlFirmado; }
    public byte[] getXmlAutorizado() { return xmlAutorizado; }
    public String getNumeroAutorizacion() { return numeroAutorizacion; }
    public Instant getFechaAutorizacion() { return fechaAutorizacion; }
    public String getMensajeSri() { return mensajeSri; }
    public String getCodigoErrorSri() { return codigoErrorSri; }
    public int getIntentosEnvio() { return intentosEnvio; }
    public Instant getUltimoIntentoAt() { return ultimoIntentoAt; }
    public Instant getSiguienteIntentoAt() { return siguienteIntentoAt; }
    public UUID getCreadoPorUsuarioId() { return creadoPorUsuarioId; }
    public Instant getCreadoAt() { return creadoAt; }
    public Instant getActualizadoAt() { return actualizadoAt; }

    public void setCreadoPorUsuarioId(UUID id) { this.creadoPorUsuarioId = id; }
}
