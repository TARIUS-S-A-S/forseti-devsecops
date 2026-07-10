package ec.tarius.forseti.shared.errors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Handler global de errores: respuesta JSON consistente sin filtrar stack trace.
 * El traceId permite correlacionar con logs del servidor.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handleApp(AppException ex, HttpServletRequest req) {
        ErrorResponse body = errorResponse(ex.getCode().name(), ex.getMessage(), req);
        if (ex.getCode().status().is5xxServerError()) {
            log.error("AppException 5xx traceId={} code={}", body.traceId, ex.getCode(), ex);
        } else {
            log.warn("AppException traceId={} code={} msg={}", body.traceId, ex.getCode(), ex.getMessage());
        }
        return ResponseEntity.status(ex.getCode().status()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .orElse("Validación falló");
        return ResponseEntity.badRequest().body(errorResponse("VALIDACION", message, req));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraint(ConstraintViolationException ex, HttpServletRequest req) {
        return ResponseEntity.badRequest().body(errorResponse("VALIDACION", ex.getMessage(), req));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        return ResponseEntity.status(403).body(errorResponse("PROHIBIDO", "Sin permisos", req));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAny(Exception ex, HttpServletRequest req) {
        ErrorResponse body = errorResponse("ERROR_INTERNO", "Error interno", req);
        log.error("Excepción no manejada traceId={}", body.traceId, ex);
        return ResponseEntity.internalServerError().body(body);
    }

    private ErrorResponse errorResponse(String code, String message, HttpServletRequest req) {
        return new ErrorResponse(code, message, req.getRequestURI(), Instant.now(), UUID.randomUUID().toString());
    }

    public record ErrorResponse(
        String code,
        String message,
        String path,
        Instant timestamp,
        String traceId
    ) {}
}
