package ec.tarius.forseti.empresa;

import ec.tarius.forseti.empresa.domain.PuntoEmision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PuntoEmisionRepository extends JpaRepository<PuntoEmision, UUID> {

    List<PuntoEmision> findByEstablecimientoIdOrderByCodigoAsc(UUID establecimientoId);

    Optional<PuntoEmision> findByEstablecimientoIdAndCodigo(UUID establecimientoId, String codigo);

    boolean existsByEstablecimientoIdAndCodigo(UUID establecimientoId, String codigo);
}
