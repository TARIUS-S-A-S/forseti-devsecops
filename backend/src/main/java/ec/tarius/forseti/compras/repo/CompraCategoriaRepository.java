package ec.tarius.forseti.compras.repo;

import ec.tarius.forseti.compras.domain.CompraCategoria;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompraCategoriaRepository extends JpaRepository<CompraCategoria, UUID> {

    List<CompraCategoria> findByActivaTrueOrderByOrden();

    Optional<CompraCategoria> findByCodigo(String codigo);
}
