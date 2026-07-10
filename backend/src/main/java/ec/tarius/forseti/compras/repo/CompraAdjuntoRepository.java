package ec.tarius.forseti.compras.repo;

import ec.tarius.forseti.compras.domain.CompraAdjunto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompraAdjuntoRepository extends JpaRepository<CompraAdjunto, UUID> {

    List<CompraAdjunto> findByCompraIdOrderByCreadoAtAsc(UUID compraId);

    /** Detecta duplicado por sha256 — filtra por empresa también (defensa en profundidad). */
    Optional<CompraAdjunto> findByEmpresaIdAndCompraIdAndSha256(
        UUID empresaId, UUID compraId, String sha256);

    /** Conteo de adjuntos agrupado por compra — evita N+1 al listar compras. */
    @org.springframework.data.jpa.repository.Query(
        "SELECT a.compraId AS compraId, COUNT(a) AS total "
      + "FROM CompraAdjunto a WHERE a.compraId IN :compraIds GROUP BY a.compraId")
    List<CompraAdjuntoCount> countByCompraIds(java.util.Collection<UUID> compraIds);

    interface CompraAdjuntoCount {
        UUID getCompraId();
        long getTotal();
    }
}
