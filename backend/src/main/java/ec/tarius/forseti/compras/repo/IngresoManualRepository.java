package ec.tarius.forseti.compras.repo;

import ec.tarius.forseti.compras.domain.IngresoManual;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface IngresoManualRepository extends JpaRepository<IngresoManual, UUID> {

    /** Ingresos del período de UNA empresa (defensa en profundidad sobre RLS). */
    List<IngresoManual> findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(
        UUID empresaId, LocalDate desde, LocalDate hasta);

    @Query("SELECT i FROM IngresoManual i WHERE i.empresaId = :empresaId AND i.anulada = false"
         + " AND i.estadoCobro <> 'COBRADO' ORDER BY i.fechaEmision DESC")
    List<IngresoManual> findPendientesCobro(UUID empresaId);
}
