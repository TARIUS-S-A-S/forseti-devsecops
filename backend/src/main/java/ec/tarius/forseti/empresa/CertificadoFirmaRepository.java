package ec.tarius.forseti.empresa;

import ec.tarius.forseti.empresa.domain.CertificadoFirma;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CertificadoFirmaRepository extends JpaRepository<CertificadoFirma, UUID> {

    /** El único certificado activo de la empresa (índice único parcial garantiza ≤1). */
    @Query("SELECT c FROM CertificadoFirma c WHERE c.empresaId = :empresaId AND c.activo = true")
    Optional<CertificadoFirma> findActivo(@Param("empresaId") UUID empresaId);

    /** Historial completo (activos + desactivados). */
    @Query("SELECT c FROM CertificadoFirma c WHERE c.empresaId = :empresaId ORDER BY c.cargadoAt DESC")
    List<CertificadoFirma> findHistorial(@Param("empresaId") UUID empresaId);
}
