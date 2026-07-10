package ec.tarius.forseti.empresa;

import ec.tarius.forseti.empresa.domain.Secuencial;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SecuencialRepository extends JpaRepository<Secuencial, UUID> {

    /**
     * SELECT … FOR UPDATE: bloquea la fila para que asignaciones concurrentes serialicen.
     * Llamar SIEMPRE dentro de @Transactional. Gate del Sprint 3.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT s FROM Secuencial s
        WHERE s.puntoEmisionId = :puntoEmisionId
          AND s.tipoComprobante = :tipo
          AND s.ambiente = :ambiente
        """)
    Optional<Secuencial> findForUpdate(@Param("puntoEmisionId") UUID puntoEmisionId,
                                        @Param("tipo") Secuencial.TipoComprobante tipo,
                                        @Param("ambiente") Secuencial.Ambiente ambiente);

    /**
     * Cuántos secuenciales tiene la empresa configurados en un ambiente. Usado por
     * EmpresaService.cambiarAmbiente() para validar que la empresa tenga al menos
     * UN punto de emisión con secuencial en PRODUCCION antes de permitir el switch.
     */
    @Query("""
        SELECT COUNT(s) FROM Secuencial s
        JOIN PuntoEmision pe ON pe.id = s.puntoEmisionId
        JOIN Establecimiento est ON est.id = pe.establecimientoId
        WHERE est.empresaId = :empresaId AND s.ambiente = :ambiente
    """)
    long contarPorEmpresaYAmbiente(@Param("empresaId") UUID empresaId,
                                    @Param("ambiente") Secuencial.Ambiente ambiente);

    java.util.List<Secuencial> findByPuntoEmisionIdOrderByAmbienteAscTipoComprobanteAsc(UUID puntoEmisionId);
}
