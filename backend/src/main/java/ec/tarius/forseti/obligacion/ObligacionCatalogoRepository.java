package ec.tarius.forseti.obligacion;

import ec.tarius.forseti.obligacion.domain.ObligacionCatalogo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ObligacionCatalogoRepository extends JpaRepository<ObligacionCatalogo, String> {

    List<ObligacionCatalogo> findByActivaTrueOrderByOrdenAsc();
}
