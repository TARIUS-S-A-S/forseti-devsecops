package ec.tarius.forseti;

import ec.tarius.forseti.shared.PublicController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Smoke test del controller público. La auth real se valida en Sprint 1 con Testcontainers.
 */
@WebMvcTest(
    controllers = PublicController.class,
    excludeAutoConfiguration = SecurityAutoConfiguration.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "ec\\.tarius\\.forseti\\.auth\\..*"
    )
)
@AutoConfigureMockMvc(addFilters = false)
class PublicControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void info_returns_basic_product_metadata() throws Exception {
        mvc.perform(get("/api/v1/public/info"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.product").value("Forseti"))
            .andExpect(jsonPath("$.company").value("TARIUS S.A.S"))
            .andExpect(jsonPath("$.generatedAt").exists());
    }
}
