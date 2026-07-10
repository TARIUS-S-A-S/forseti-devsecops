package ec.tarius.forseti.emision;

import ec.tarius.forseti.emision.domain.ComprobanteDetalle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ComprobanteDetalleRepository extends JpaRepository<ComprobanteDetalle, UUID> {

    List<ComprobanteDetalle> findByComprobanteIdOrderByOrdenAsc(UUID comprobanteId);
}
