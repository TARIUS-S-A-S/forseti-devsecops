package ec.tarius.forseti.obligacion;

import ec.tarius.forseti.obligacion.domain.ObligacionEmpresa;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ObligacionEmpresaRepository extends JpaRepository<ObligacionEmpresa, UUID> {

    List<ObligacionEmpresa> findByEmpresaId(UUID empresaId);

    Optional<ObligacionEmpresa> findByEmpresaIdAndObligacionCodigo(UUID empresaId, String obligacionCodigo);
}
