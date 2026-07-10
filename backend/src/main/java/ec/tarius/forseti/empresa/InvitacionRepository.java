package ec.tarius.forseti.empresa;

import ec.tarius.forseti.empresa.domain.Invitacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvitacionRepository extends JpaRepository<Invitacion, UUID> {

    Optional<Invitacion> findByToken(String token);

    @Query("""
        SELECT i FROM Invitacion i
        WHERE i.empresaId = :empresaId
          AND i.aceptadaAt IS NULL
          AND i.canceladaAt IS NULL
        ORDER BY i.creadaAt DESC
        """)
    List<Invitacion> findPendientes(@Param("empresaId") UUID empresaId);

    @Query("""
        SELECT i FROM Invitacion i
        WHERE i.empresaId = :empresaId AND LOWER(i.email) = LOWER(:email)
          AND i.aceptadaAt IS NULL AND i.canceladaAt IS NULL
        """)
    Optional<Invitacion> findPendientePorEmail(@Param("empresaId") UUID empresaId,
                                                 @Param("email") String email);
}
