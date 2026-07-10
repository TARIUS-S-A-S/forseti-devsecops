package ec.tarius.forseti.emision;

import ec.tarius.forseti.emision.domain.Comprobante;
import ec.tarius.forseti.emision.domain.Comprobante.Estado;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ComprobanteRepository extends JpaRepository<Comprobante, UUID> {

    Optional<Comprobante> findByClaveAcceso(String claveAcceso);

    boolean existsByEmpresaIdAndTipoComprobanteAndAmbienteAndNumeroComprobante(
        UUID empresaId, String tipoComprobante, String ambiente, String numeroComprobante);

    List<Comprobante> findByEmpresaIdAndEstadoOrderByCreadoAtDesc(UUID empresaId, Estado estado);

    List<Comprobante> findByEmpresaIdOrderByCreadoAtDesc(UUID empresaId);

    /** Comprobantes emitidos en un rango de fechas (RLS filtra por empresa). HU-F4/F5. */
    List<Comprobante> findByFechaEmisionBetween(LocalDate desde, LocalDate hasta);

    /**
     * Comprobantes emitidos por UNA empresa en un rango — defensa en profundidad sobre RLS.
     * Reportes financieros (HU-F4/F5) deben usar este método, NO el genérico, para evitar
     * que un bug en RLS filtre datos de otras empresas a un reporte.
     */
    List<Comprobante> findByEmpresaIdAndFechaEmisionBetween(
        UUID empresaId, LocalDate desde, LocalDate hasta);

    /**
     * Comprobantes pendientes de procesar por el worker (JobRunr).
     * Devuelve los que están en FIRMADA o ENVIADA o EN_PROCESO con siguiente_intento_at vencido,
     * O sin siguiente_intento_at (recién encolados).
     */
    @Query("""
        SELECT c FROM Comprobante c
        WHERE c.estado IN ('FIRMADA','ENVIADA','EN_PROCESO')
          AND (c.siguienteIntentoAt IS NULL OR c.siguienteIntentoAt <= :ahora)
          AND c.intentosEnvio < :maxIntentos
        """)
    List<Comprobante> findPendientesProcesar(@Param("ahora") Instant ahora,
                                              @Param("maxIntentos") int maxIntentos);

    // ─────────────────────────────────────────────────────────────────────
    // Métricas de cola (Fase E dashboard)
    // ─────────────────────────────────────────────────────────────────────

    /** Cuántos comprobantes hay en cada estado para la empresa activa. */
    @Query("""
        SELECT c.estado AS estado, COUNT(c) AS total
        FROM Comprobante c
        WHERE c.empresaId = :empresaId
        GROUP BY c.estado
    """)
    List<EstadoConteo> conteoPorEstado(@Param("empresaId") UUID empresaId);

    /** Más viejo en estado FIRMADA/ENVIADA/EN_PROCESO esperando autorización. */
    @Query("""
        SELECT MIN(c.creadoAt) FROM Comprobante c
        WHERE c.empresaId = :empresaId
          AND c.estado IN ('FIRMADA','ENVIADA','EN_PROCESO')
    """)
    Optional<Instant> masViejoPendiente(@Param("empresaId") UUID empresaId);

    /** Total emitidos en las últimas 24h, para calcular tasa de éxito. */
    @Query("""
        SELECT COUNT(c) FROM Comprobante c
        WHERE c.empresaId = :empresaId
          AND c.creadoAt > :desde
    """)
    long total24h(@Param("empresaId") UUID empresaId, @Param("desde") Instant desde);

    @Query("""
        SELECT COUNT(c) FROM Comprobante c
        WHERE c.empresaId = :empresaId
          AND c.creadoAt > :desde
          AND c.estado = 'AUTORIZADA'
    """)
    long autorizados24h(@Param("empresaId") UUID empresaId, @Param("desde") Instant desde);

    /** Tiempo promedio en segundos entre FIRMADA y AUTORIZADA en las últimas 24h. */
    @Query(value = """
        SELECT EXTRACT(EPOCH FROM AVG(c.fecha_autorizacion - c.creado_at))
        FROM comprobante c
        WHERE c.empresa_id = :empresaId
          AND c.creado_at > :desde
          AND c.estado = 'AUTORIZADA'
          AND c.fecha_autorizacion IS NOT NULL
    """, nativeQuery = true)
    Double segundosPromedioAutorizacion24h(@Param("empresaId") UUID empresaId,
                                           @Param("desde") Instant desde);

    interface EstadoConteo {
        String getEstado();
        long getTotal();
    }

    /**
     * Cuántos comprobantes firmó este cert. Si > 0, el cert NO se puede eliminar
     * definitivamente (solo desactivar) — preserva trazabilidad legal.
     */
    long countByCertificadoId(UUID certificadoId);
}
