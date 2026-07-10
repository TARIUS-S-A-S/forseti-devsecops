package ec.tarius.forseti.emision;

import ec.tarius.forseti.emision.domain.ComprobanteEvento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ComprobanteEventoRepository extends JpaRepository<ComprobanteEvento, UUID> {

    List<ComprobanteEvento> findByComprobanteIdOrderByCreadoAtAsc(UUID comprobanteId);
}
