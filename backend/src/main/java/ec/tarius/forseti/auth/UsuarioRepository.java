package ec.tarius.forseti.auth;

import ec.tarius.forseti.auth.domain.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {

    Optional<Usuario> findByEmail(String email);

    Optional<Usuario> findByUsername(String username);

    Optional<Usuario> findByVerificacionToken(String token);

    Optional<Usuario> findByRecoveryToken(String token);

    boolean existsByEmail(String email);

    boolean existsByUsername(String username);
}
