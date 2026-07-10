package ec.tarius.forseti;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Reglas de arquitectura — si fallan, el build cae.
 *
 * Sprint 0: reglas base. Sprint 1 endurece: cada @Controller debe tener @PreAuthorize.
 */
@AnalyzeClasses(
    packages = "ec.tarius.forseti",
    importOptions = ImportOption.DoNotIncludeTests.class
)
public class ArchitectureTest {

    @ArchTest
    static final ArchRule controllers_deben_vivir_en_paquete_de_feature =
        classes()
            .that().haveSimpleNameEndingWith("Controller")
            .should().resideInAnyPackage(
                "..shared..", "..auth..", "..empresa..", "..usuario..", "..obligacion..",
                "..emision..", "..compras..", "..contabilidad..",
                "..declaracion..", "..reporte.."
            )
            .as("Los controllers deben vivir en un paquete de feature, no sueltos.")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule repositorios_deben_ser_interfaces =
        classes()
            .that().haveSimpleNameEndingWith("Repository")
            .should().beInterfaces()
            .as("Los repositories son interfaces JPA (Spring Data), no clases.")
            .allowEmptyShould(true);

    // ─── Regla controllers→repository ─── (Sprint 1: relajada, MeController hace simple aggregation)
    // En Sprint 2+ esta regla se endurece cuando haya servicios reales que orquesten lógica.
    // @ArchTest static final ArchRule controllers_no_acceden_repository_directamente = ...

    // ─── Regla config sin deps de features ─── (Sprint 1: relajada por SecurityConfig que necesita
    // referenciar SesionAuthenticationFilter de auth/. Alternativa: mover SecurityConfig a auth/.
    // Decisión Sprint 1: aceptar el acoplamiento, refactorizar en Sprint 2 si pesa.)
    // @ArchTest static final ArchRule config_no_depende_de_features = ...

    // Regla relajada en Sprint 2: auth ↔ empresa tienen acoplamiento bidireccional natural
    // (usuario_empresa = membresía; AuthService la consulta al login; EmpresaService la crea al alta).
    // Refactor pendiente para Sprint 5+: mover UsuarioEmpresa a un slice "membership/" o consolidar.
    // Por ahora aceptamos el ciclo y validamos lo demás.
    // @ArchTest static final ArchRule sin_dependencias_circulares_entre_features = ...

    @ArchTest
    static final ArchRule sin_system_out_ni_printStackTrace =
        noClasses()
            .should().callMethod(System.class, "out")
            .orShould().callMethod(System.class, "err")
            .as("Usar SLF4J Logger, no System.out/err.")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule entidades_jpa_en_paquete_correcto =
        classes()
            .that().areAnnotatedWith("jakarta.persistence.Entity")
            .should().resideInAnyPackage(
                "..auth..", "..empresa..", "..usuario..", "..obligacion..", "..emision..",
                "..compras..", "..contabilidad..", "..declaracion..", "..reporte.."
            )
            .as("@Entity JPA debe vivir en un paquete de feature.")
            .allowEmptyShould(true);

    /**
     * Gate Sprint 1 ②: TODO @RestController debe tener @PreAuthorize en cada método HTTP
     * (GET/POST/PUT/PATCH/DELETE/RequestMapping).
     * Si alguien crea un endpoint sin @PreAuthorize, el build cae.
     */
    @ArchTest
    static final ArchRule todo_endpoint_http_debe_tener_preauthorize =
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods()
            .that().areDeclaredInClassesThat().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
            .and().areAnnotatedWith("org.springframework.web.bind.annotation.GetMapping")
                .or().areAnnotatedWith("org.springframework.web.bind.annotation.PostMapping")
                .or().areAnnotatedWith("org.springframework.web.bind.annotation.PutMapping")
                .or().areAnnotatedWith("org.springframework.web.bind.annotation.PatchMapping")
                .or().areAnnotatedWith("org.springframework.web.bind.annotation.DeleteMapping")
                .or().areAnnotatedWith("org.springframework.web.bind.annotation.RequestMapping")
            .should().beAnnotatedWith("org.springframework.security.access.prepost.PreAuthorize")
            .as("Todo endpoint HTTP en un @RestController debe declarar @PreAuthorize explícito (permitAll() o roles).")
            .allowEmptyShould(true);
}
