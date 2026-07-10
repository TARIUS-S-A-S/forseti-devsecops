package ec.tarius.forseti.compras.repo;

import ec.tarius.forseti.compras.domain.Compra;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompraRepository extends JpaRepository<Compra, UUID> {

    /**
     * Compras del período de UNA empresa específica (defensa en profundidad sobre RLS).
     * Incluye anuladas; usar filtro {@code anulada=false} en lectura cuando aplique.
     */
    List<Compra> findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(
        UUID empresaId, LocalDate desde, LocalDate hasta);

    /**
     * Búsqueda por proveedor + número PARA UNA EMPRESA (detecta duplicados pre-XML).
     * Filtra explícitamente por empresaId: defensa en profundidad sobre RLS. Si RLS
     * está roto (bug histórico del {@code TenantTransactionAdvice @Order}), este
     * método sigue devolviendo solo las compras de la empresa pedida.
     */
    Optional<Compra> findByEmpresaIdAndProveedorIdentificacionAndTipoDocumentoAndNumeroDocumento(
        UUID empresaId, String proveedorIdent, Compra.TipoDocumento tipoDoc, String numero);

    /** Compras pendientes de pago de la empresa actual (HU-F4 flujo de caja). */
    @Query("SELECT c FROM Compra c WHERE c.empresaId = :empresaId AND c.anulada = false"
         + " AND c.estadoPago <> 'PAGADO' ORDER BY c.fechaEmision DESC")
    List<Compra> findPendientesPago(UUID empresaId);
}
