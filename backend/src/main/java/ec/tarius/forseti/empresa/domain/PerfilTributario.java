package ec.tarius.forseti.empresa.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Vigencias del perfil tributario de una empresa.
 * Cambiar régimen / periodicidad NO pisa el historial: se cierra la vigencia actual y se crea una nueva.
 * El "perfil vigente" a una fecha dada se consulta con perfil_vigente_a(empresa, fecha) o con
 * el método del servicio.
 */
@Entity
@Table(name = "perfil_tributario")
public class PerfilTributario {

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Column(name = "vigente_desde", nullable = false)
    private LocalDate vigenteDesde;

    /** NULL = vigencia abierta (perfil actual). */
    @Column(name = "vigente_hasta")
    private LocalDate vigenteHasta;

    @Column(name = "regimen_tributario", nullable = false, length = 30)
    private String regimenTributario;

    @Column(name = "periodicidad_iva", nullable = false, length = 20)
    private String periodicidadIva;

    @Column(name = "obligado_contabilidad", nullable = false)
    private boolean obligadoContabilidad;

    @Column(name = "agente_retencion", nullable = false)
    private boolean agenteRetencion;

    @Column(name = "motivo_cambio", columnDefinition = "text")
    private String motivoCambio;

    @Column(name = "creado_por_usuario_id")
    private UUID creadoPorUsuarioId;

    @Column(name = "creado_at", nullable = false, updatable = false)
    private Instant creadoAt = Instant.now();

    protected PerfilTributario() {}

    public static PerfilTributario nuevo(UUID empresaId, LocalDate vigenteDesde,
                                          String regimen, String periodicidad,
                                          boolean obligadoContabilidad, boolean agenteRetencion,
                                          UUID creadoPor) {
        PerfilTributario p = new PerfilTributario();
        p.empresaId = empresaId;
        p.vigenteDesde = vigenteDesde;
        p.regimenTributario = regimen;
        p.periodicidadIva = periodicidad;
        p.obligadoContabilidad = obligadoContabilidad;
        p.agenteRetencion = agenteRetencion;
        p.creadoPorUsuarioId = creadoPor;
        return p;
    }

    public void cerrarVigencia(LocalDate hasta, String motivo) {
        this.vigenteHasta = hasta;
        this.motivoCambio = motivo;
    }

    public boolean isVigente() {
        return vigenteHasta == null;
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getEmpresaId() { return empresaId; }
    public LocalDate getVigenteDesde() { return vigenteDesde; }
    public LocalDate getVigenteHasta() { return vigenteHasta; }
    public String getRegimenTributario() { return regimenTributario; }
    public String getPeriodicidadIva() { return periodicidadIva; }
    public boolean isObligadoContabilidad() { return obligadoContabilidad; }
    public boolean isAgenteRetencion() { return agenteRetencion; }
    public String getMotivoCambio() { return motivoCambio; }
    public UUID getCreadoPorUsuarioId() { return creadoPorUsuarioId; }
    public Instant getCreadoAt() { return creadoAt; }
}
