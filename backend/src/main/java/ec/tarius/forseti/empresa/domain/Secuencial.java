package ec.tarius.forseti.empresa.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Contador transaccional por (punto_emision, tipo_comprobante, ambiente).
 * IMPORTANTE: el repo usa SELECT ... FOR UPDATE al asignar para evitar saltos / duplicados
 * bajo concurrencia. Gate del Sprint 3 (100 emisiones paralelas sin colisión).
 */
@Entity
@Table(name = "secuencial")
public class Secuencial {

    public enum TipoComprobante {
        FACTURA, NOTA_CREDITO, NOTA_DEBITO, RETENCION, GUIA_REMISION, LIQUIDACION_COMPRA
    }

    public enum Ambiente {
        PRUEBAS, PRODUCCION
    }

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Column(name = "punto_emision_id", nullable = false)
    private UUID puntoEmisionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_comprobante", nullable = false, length = 30)
    private TipoComprobante tipoComprobante;

    @Column(name = "proximo_numero", nullable = false)
    private long proximoNumero = 1L;

    @Enumerated(EnumType.STRING)
    @Column(name = "ambiente", nullable = false, length = 15)
    private Ambiente ambiente = Ambiente.PRUEBAS;

    @Column(name = "actualizado_at", nullable = false)
    private Instant actualizadoAt = Instant.now();

    protected Secuencial() {}

    public static Secuencial nuevo(UUID empresaId, UUID puntoEmisionId,
                                    TipoComprobante tipo, Ambiente ambiente,
                                    long proximoNumero) {
        Secuencial s = new Secuencial();
        s.empresaId = empresaId;
        s.puntoEmisionId = puntoEmisionId;
        s.tipoComprobante = tipo;
        s.ambiente = ambiente;
        s.proximoNumero = proximoNumero;
        return s;
    }

    /** Asigna el siguiente número y lo incrementa. Usar SOLO dentro de una TX con FOR UPDATE. */
    public long asignarYAvanzar() {
        long actual = this.proximoNumero;
        this.proximoNumero = actual + 1;
        return actual;
    }

    public UUID getId() { return id; }
    public UUID getEmpresaId() { return empresaId; }
    public UUID getPuntoEmisionId() { return puntoEmisionId; }
    public TipoComprobante getTipoComprobante() { return tipoComprobante; }
    public long getProximoNumero() { return proximoNumero; }
    /** Setter para migración: NO usar en el flujo normal (asignarYAvanzar() lo maneja). */
    public void setProximoNumero(long proximoNumero) {
        if (proximoNumero < 1) throw new IllegalArgumentException("proximoNumero >= 1");
        this.proximoNumero = proximoNumero;
    }
    public Ambiente getAmbiente() { return ambiente; }
    public Instant getActualizadoAt() { return actualizadoAt; }
}
