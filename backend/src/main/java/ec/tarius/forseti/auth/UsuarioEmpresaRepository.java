package ec.tarius.forseti.auth;

import ec.tarius.forseti.auth.domain.UsuarioEmpresa;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UsuarioEmpresaRepository extends JpaRepository<UsuarioEmpresa, UUID> {

    List<UsuarioEmpresa> findByUsuarioId(UUID usuarioId);

    Optional<UsuarioEmpresa> findByUsuarioIdAndEmpresaId(UUID usuarioId, UUID empresaId);

    /** Solo accesible si el caller seteó app.gestor_de_empresa = empresaId (ver policy V9). */
    List<UsuarioEmpresa> findByEmpresaId(UUID empresaId);
}
