package ec.tarius.forseti.auth;

import ec.tarius.forseti.auth.domain.Sesion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SesionRepository extends JpaRepository<Sesion, UUID> {

    Optional<Sesion> findByTokenHash(String tokenHash);

    @Modifying
    @Query("UPDATE Sesion s SET s.revocadaAt = :ts WHERE s.usuarioId = :usuarioId AND s.revocadaAt IS NULL")
    int revocarTodasDelUsuario(@Param("usuarioId") UUID usuarioId, @Param("ts") Instant ts);

    @Modifying
    @Query("DELETE FROM Sesion s WHERE s.expiraAt < :ts OR s.revocadaAt IS NOT NULL AND s.revocadaAt < :ts")
    int eliminarExpiradasYRevocadas(@Param("ts") Instant antesDe);

    /**
     * Cambia la empresa activa en TODAS las sesiones vigentes del usuario.
     * Sprint 2: usado por seleccionarEmpresaActiva. Próximo request lee la cookie y ve la nueva.
     */
    @Modifying
    @Query("UPDATE Sesion s SET s.empresaActivaId = :empresaId " +
           "WHERE s.usuarioId = :usuarioId AND s.revocadaAt IS NULL")
    int actualizarEmpresaActivaDelUsuario(@Param("usuarioId") UUID usuarioId,
                                            @Param("empresaId") UUID empresaId);

    /**
     * Si la empresa activa de cualquier sesión vigente del usuario era ESTA empresa, la
     * vacía. Usado al archivar para que el usuario no quede con una empresa activa
     * archivada.
     */
    @Modifying
    @Query("UPDATE Sesion s SET s.empresaActivaId = NULL " +
           "WHERE s.usuarioId = :usuarioId AND s.empresaActivaId = :empresaId " +
           "  AND s.revocadaAt IS NULL")
    int limpiarEmpresaActivaDelUsuarioSiEs(@Param("usuarioId") UUID usuarioId,
                                            @Param("empresaId") UUID empresaId);
}
