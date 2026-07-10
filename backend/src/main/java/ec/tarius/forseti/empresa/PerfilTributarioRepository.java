package ec.tarius.forseti.empresa;

import ec.tarius.forseti.empresa.domain.PerfilTributario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PerfilTributarioRepository extends JpaRepository<PerfilTributario, UUID> {

    /** Perfil con vigencia abierta (NULL en vigente_hasta) — el actual. */
    @Query("SELECT p FROM PerfilTributario p WHERE p.empresaId = :empresaId AND p.vigenteHasta IS NULL")
    Optional<PerfilTributario> findVigenteActual(@Param("empresaId") UUID empresaId);

    /** Historial completo, vigencia más reciente primero. */
    @Query("SELECT p FROM PerfilTributario p WHERE p.empresaId = :empresaId ORDER BY p.vigenteDesde DESC")
    List<PerfilTributario> findHistorial(@Param("empresaId") UUID empresaId);

    /** Perfil vigente a una fecha dada (para declarar periodos pasados). */
    @Query("""
        SELECT p FROM PerfilTributario p
        WHERE p.empresaId = :empresaId
          AND p.vigenteDesde <= :fecha
          AND (p.vigenteHasta IS NULL OR p.vigenteHasta > :fecha)
        ORDER BY p.vigenteDesde DESC
        """)
    List<PerfilTributario> findVigenteAFecha(@Param("empresaId") UUID empresaId,
                                               @Param("fecha") LocalDate fecha);
}
