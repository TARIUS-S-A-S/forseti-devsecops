package ec.tarius.forseti.auth;

import ec.tarius.forseti.auth.domain.Sesion;
import ec.tarius.forseti.auth.domain.Usuario;
import ec.tarius.forseti.shared.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lee la cookie __Host-FORSETI_SESSION y, si es válida:
 * - Carga la sesión + usuario
 * - Setea SecurityContext con UsernamePasswordAuthenticationToken
 * - Setea TenantContext (empresaActivaId + usuarioId) → activa RLS en cada TX
 *
 * Si la cookie no existe o la sesión es inválida, deja request anónimo (no rompe).
 * El SecurityConfig decide si el endpoint requiere auth o no.
 */
@Component
public class SesionAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SesionAuthenticationFilter.class);

    private final AuthService authService;
    private final UsuarioRepository usuarioRepo;

    public SesionAuthenticationFilter(AuthService authService, UsuarioRepository usuarioRepo) {
        this.authService = authService;
        this.usuarioRepo = usuarioRepo;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            Optional<String> token = leerCookieSesion(request);
            if (token.isPresent()) {
                Optional<Sesion> sesionOpt = authService.sesionPorToken(token.get());
                if (sesionOpt.isPresent()) {
                    Sesion sesion = sesionOpt.get();
                    Optional<Usuario> userOpt = usuarioRepo.findById(sesion.getUsuarioId());
                    if (userOpt.isPresent() && userOpt.get().isActivo()) {
                        Usuario user = userOpt.get();
                        autenticar(user, sesion);
                    }
                }
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void autenticar(Usuario user, Sesion sesion) {
        // Roles base — Sprint 2 enriquece con rol por empresa
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));

        UsernamePasswordAuthenticationToken auth =
            new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);

        // TenantContext — las queries dentro de @Transactional verán SET LOCAL automáticamente
        TenantContext.setUsuarioId(user.getId());
        if (sesion.getEmpresaActivaId() != null) {
            TenantContext.setEmpresaId(sesion.getEmpresaActivaId());
        }
    }

    private Optional<String> leerCookieSesion(HttpServletRequest req) {
        if (req.getCookies() == null) return Optional.empty();
        for (Cookie c : req.getCookies()) {
            if (AuthController.SESSION_COOKIE.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return Optional.of(c.getValue());
            }
        }
        return Optional.empty();
    }
}
