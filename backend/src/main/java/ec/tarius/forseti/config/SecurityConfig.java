package ec.tarius.forseti.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import ec.tarius.forseti.auth.SesionAuthenticationFilter;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)  // Sprint 1: @PreAuthorize obligatorio (ArchUnit valida)
public class SecurityConfig {

    /**
     * Argon2id — recomendado OWASP 2024+ para hash de passwords.
     * Parámetros: saltLength=16, hashLength=32, parallelism=1, memory=19_456 KB, iterations=2
     * Balance seguridad/performance para VPS 1GB RAM.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
    }

    /**
     * CORS estricto: solo orígenes confiables. Sprint 1 endurece si hay subdominio aparte.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(
            "https://forseti.tarius.ec",
            "https://staging.forseti.tarius.ec",
            "http://localhost:5173"      // Vite dev
        ));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-XSRF-TOKEN", "X-Request-Id"));
        cors.setExposedHeaders(List.of("X-Request-Id"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    /**
     * Política deny-by-default. Cada feature opta-in explícitamente con @PreAuthorize.
     * Sprint 0: solo healthcheck e info públicos.
     * Sprint 1: agregar /api/v1/auth/login, /register, /verify-email (también permitAll).
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                    SesionAuthenticationFilter sesionFilter) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null); // No usar BREACH protection (compatible con SPA axios)

        http
            // CSRF: cookie XSRF-TOKEN no-httpOnly para que axios la lea + mande en X-XSRF-TOKEN
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfHandler)
                .ignoringRequestMatchers(
                    "/api/v1/public/**",
                    "/actuator/**",
                    // Endpoints de auth pre-login — protegidos por rate limiting bucket4j,
                    // no por CSRF (chicken-and-egg: no hay sesión todavía para tener token CSRF)
                    "/api/v1/auth/register",
                    "/api/v1/auth/verify-email",
                    "/api/v1/auth/login",
                    "/api/v1/auth/recovery",
                    "/api/v1/auth/reset-password"
                )
            )
            // CORS estricto
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            // Stateless en servlet container — la sesión vive en DB (server-side),
            // el filter custom lee la cookie y setea SecurityContext + TenantContext.
            .sessionManagement(sm -> sm.sessionCreationPolicy(
                org.springframework.security.config.http.SessionCreationPolicy.STATELESS
            ))
            .addFilterBefore(sesionFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(authz -> authz
                // Endpoints públicos explícitamente permitidos
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/api/v1/public/**").permitAll()
                // Endpoints de auth — siempre permitAll (la lógica de auth está en AuthService)
                .requestMatchers("/api/v1/auth/register").permitAll()
                .requestMatchers("/api/v1/auth/verify-email").permitAll()
                .requestMatchers("/api/v1/auth/login").permitAll()
                .requestMatchers("/api/v1/auth/logout").permitAll()
                .requestMatchers("/api/v1/auth/recovery").permitAll()
                .requestMatchers("/api/v1/auth/reset-password").permitAll()
                .requestMatchers("/api/v1/auth/cambiar-password").authenticated()
                // Invitaciones: GET es público (token actúa como auth); POST /aceptar requiere session
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/invitaciones/**").permitAll()
                .requestMatchers("/api/v1/invitaciones/**").authenticated()
                // Endpoints autenticados — @PreAuthorize dentro del controller refina más
                .requestMatchers("/api/v1/auth/me").authenticated()
                .requestMatchers("/api/v1/auth/2fa/**").authenticated()
                .requestMatchers("/api/v1/empresas/**").authenticated()
                .requestMatchers("/api/v1/obligaciones/**").authenticated()
                .requestMatchers("/api/v1/comprobantes/**").authenticated()
                // Estado del SRI: público (la UI lo lee desde la pantalla de login también
                // para mostrar banner si SRI está caído antes de entrar). Solo dato agregado,
                // sin info sensible.
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/sri/estado").permitAll()
                // Cola SRI (métricas por empresa): requiere session + empresa activa
                .requestMatchers("/api/v1/sri/**").authenticated()
                // TODO LO DEMÁS denegado por default (incluye /actuator/info, env, etc.)
                .anyRequest().denyAll()
            )
            .headers(headers -> headers
                .contentTypeOptions(c -> {})
                .frameOptions(f -> f.deny())
                .httpStrictTransportSecurity(h -> h.includeSubDomains(true).maxAgeInSeconds(31_536_000).preload(true))
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                // Spring 6 default ya pone X-Content-Type-Options:nosniff y X-XSS-Protection:0
                .crossOriginOpenerPolicy(coop -> coop.policy(
                    org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN
                ))
                .crossOriginResourcePolicy(corp -> corp.policy(
                    org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy.SAME_ORIGIN
                ))
            );
        return http.build();
    }
}
