package ec.tarius.forseti.emision.sri;

import java.util.Set;

/**
 * Clasificación de los códigos de error del SRI Ecuador en categorías accionables.
 *
 * Sirve para decidir en los handlers de job:
 *   - {@link #BUG_FIRMADOR}, {@link #DATOS_INVALIDOS}, {@link #DUPLICADO}: NO reintentar.
 *     Marcar definitivo, alertar o mostrar al usuario.
 *   - {@link #SRI_TRANSIENTE}: reintentar con backoff (el SRI está caído / saturado).
 *   - {@link #DESCONOCIDO}: tratar conservadoramente (no reintentar pero loggear para revisar).
 *
 * Códigos basados en la ficha técnica SRI v2.26 sección de mensajes de error.
 * Lista NO exhaustiva — se va completando con experiencia real.
 */
public enum CategoriaErrorSri {
    /**
     * Bug en NUESTRO firmador. Si esto aparece en prod después del fix de Sprint 3 Fase E,
     * es una regresión seria — alguien rompió la firma. NO reintentar, alertar.
     * Códigos: 39 (firma inválida).
     */
    BUG_FIRMADOR,

    /**
     * Datos del comprobante mal armados (RUC inválido, fecha futura, totales no cuadran,
     * cert caducado, etc.). NO se resuelve reintentando — hay que corregir el comprobante.
     * Códigos típicos: 43, 45 parcial, 52, 65, 68.
     */
    DATOS_INVALIDOS,

    /**
     * Clave de acceso ya registrada en SRI. Posible doble envío. NO reintentar.
     * Códigos: 35.
     */
    DUPLICADO,

    /**
     * SRI temporalmente no disponible / saturado. SÍ reintentar con backoff.
     * Códigos: 70 (servicio no disponible), a veces 45 (error general).
     */
    SRI_TRANSIENTE,

    /**
     * Código no clasificado. Por seguridad NO reintentar (para no martillar al SRI),
     * pero loggear y revisar para refinar esta tabla.
     */
    DESCONOCIDO;

    private static final Set<String> CODIGOS_BUG_FIRMADOR  = Set.of("39");
    private static final Set<String> CODIGOS_DATOS_INVAL   = Set.of("43", "52", "65", "68");
    private static final Set<String> CODIGOS_DUPLICADO     = Set.of("35");
    private static final Set<String> CODIGOS_SRI_TRANS     = Set.of("70");

    /**
     * Clasifica un código de error SRI (el "identificador" del MensajeSri).
     * Si {@code codigo} es null o vacío, devuelve {@link #DESCONOCIDO}.
     */
    public static CategoriaErrorSri de(String codigo) {
        if (codigo == null || codigo.isBlank()) return DESCONOCIDO;
        String c = codigo.trim();
        if (CODIGOS_BUG_FIRMADOR.contains(c)) return BUG_FIRMADOR;
        if (CODIGOS_DATOS_INVAL.contains(c))  return DATOS_INVALIDOS;
        if (CODIGOS_DUPLICADO.contains(c))    return DUPLICADO;
        if (CODIGOS_SRI_TRANS.contains(c))    return SRI_TRANSIENTE;
        return DESCONOCIDO;
    }

    /**
     * ¿Debe el handler de job reintentar este error? Solo {@link #SRI_TRANSIENTE} reintenta.
     */
    public boolean debeReintentar() {
        return this == SRI_TRANSIENTE;
    }

    /**
     * ¿Es un error que requiere alerta operacional? BUG_FIRMADOR es la única alarma seria
     * (significa que la firma se rompió post-deploy).
     */
    public boolean requiereAlerta() {
        return this == BUG_FIRMADOR;
    }
}
