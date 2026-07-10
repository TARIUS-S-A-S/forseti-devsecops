package ec.tarius.forseti.empresa;

import ec.tarius.forseti.empresa.domain.Establecimiento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EstablecimientoRepository extends JpaRepository<Establecimiento, UUID> {

    List<Establecimiento> findByEmpresaIdOrderByCodigoAsc(UUID empresaId);

    Optional<Establecimiento> findByEmpresaIdAndCodigo(UUID empresaId, String codigo);

    boolean existsByEmpresaIdAndCodigo(UUID empresaId, String codigo);
}
