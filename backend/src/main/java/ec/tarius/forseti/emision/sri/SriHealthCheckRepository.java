package ec.tarius.forseti.emision.sri;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface SriHealthCheckRepository extends JpaRepository<SriHealthCheck, Long> {

    /** Último check por ambiente (alimenta el endpoint /api/v1/sri/estado). */
    @Query("""
        SELECT h FROM SriHealthCheck h
        WHERE h.ambiente = :ambiente
        ORDER BY h.tsCheck DESC
        LIMIT 1
    """)
    Optional<SriHealthCheck> ultimoPor(String ambiente);
}
