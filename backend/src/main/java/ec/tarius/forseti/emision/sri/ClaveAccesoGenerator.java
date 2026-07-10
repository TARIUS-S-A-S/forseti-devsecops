package ec.tarius.forseti.emision.sri;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Genera la clave de acceso SRI (49 dígitos) según la ficha técnica de comprobantes
 * electrónicos vigente. Es determinista dada la entrada — el único input no determinista
 * es el código numérico aleatorio (8 dígitos), que el llamador puede pasar para tests.
 *
 * Estructura (48 dígitos + 1 verificador):
 *   ddMMyyyy(8) + codDoc(2) + ruc(13) + ambiente(1) + serie(6) + secuencial(9)
 *   + numericoAleatorio(8) + tipoEmision(1) + digitoVerificador(1)
 *
 * Códigos de tipo de comprobante SRI:
 *   01 FACTURA · 03 LIQUIDACION_COMPRA · 04 NOTA_CREDITO · 05 NOTA_DEBITO
 *   06 GUIA_REMISION · 07 RETENCION
 *
 * Ambiente: 1=PRUEBAS, 2=PRODUCCION.
 * Tipo emisión: 1=NORMAL (post-2024 la contingencia "2" ya no se usa).
 *
 * Dígito verificador: módulo 11 SRI — factor 2..7 reiniciado, suma de productos, mod 11,
 * resultado = 11 − mod. Si 11 → 0, si 10 → 1, sino → resultado.
 */
public final class ClaveAccesoGenerator {

    private static final DateTimeFormatter FECHA_FMT = DateTimeFormatter.ofPattern("ddMMyyyy");
    private static final SecureRandom RNG = new SecureRandom();

    private ClaveAccesoGenerator() {}

    public enum TipoDocumento {
        FACTURA("01"),
        LIQUIDACION_COMPRA("03"),
        NOTA_CREDITO("04"),
        NOTA_DEBITO("05"),
        GUIA_REMISION("06"),
        RETENCION("07");

        public final String codigo;
        TipoDocumento(String codigo) { this.codigo = codigo; }
    }

    public enum Ambiente {
        PRUEBAS("1"), PRODUCCION("2");
        public final String codigo;
        Ambiente(String codigo) { this.codigo = codigo; }
    }

    /**
     * Genera una clave de acceso usando un código numérico aleatorio nuevo (SecureRandom).
     */
    public static String generar(LocalDate fechaEmision, TipoDocumento tipo, String ruc,
                                  Ambiente ambiente, String establecimiento, String puntoEmision,
                                  long secuencial) {
        return generar(fechaEmision, tipo, ruc, ambiente, establecimiento, puntoEmision, secuencial,
            generarCodigoNumericoAleatorio());
    }

    /**
     * Genera una clave de acceso usando un código numérico explícito (útil para tests reproducibles).
     */
    public static String generar(LocalDate fechaEmision, TipoDocumento tipo, String ruc,
                                  Ambiente ambiente, String establecimiento, String puntoEmision,
                                  long secuencial, String codigoNumerico) {
        validarRuc(ruc);
        validarSerie(establecimiento, "establecimiento");
        validarSerie(puntoEmision, "puntoEmision");
        if (secuencial < 1 || secuencial > 999_999_999L) {
            throw new IllegalArgumentException("Secuencial fuera de rango (1..999.999.999): " + secuencial);
        }
        if (codigoNumerico == null || !codigoNumerico.matches("\\d{8}")) {
            throw new IllegalArgumentException("Código numérico aleatorio debe ser 8 dígitos");
        }

        String fecha = fechaEmision.format(FECHA_FMT);
        String secPadded = String.format("%09d", secuencial);
        String tipoEmision = "1";

        String sinDv = fecha
            + tipo.codigo
            + ruc
            + ambiente.codigo
            + establecimiento + puntoEmision
            + secPadded
            + codigoNumerico
            + tipoEmision;

        if (sinDv.length() != 48) {
            // Defensa: alguno de los inputs no respetó su largo declarado
            throw new IllegalStateException(
                "Clave sin verificador no mide 48 dígitos (mide " + sinDv.length() + "): " + sinDv);
        }

        return sinDv + digitoVerificadorModulo11(sinDv);
    }

    /**
     * Algoritmo módulo 11 SRI — usado tanto para validar RUC/cédula como para el dígito
     * verificador de la clave de acceso. Para la clave, recorre derecha → izquierda,
     * multiplica por 2,3,4,5,6,7,2,3,4,5,6,7… y reduce mod 11.
     */
    static char digitoVerificadorModulo11(String numeros) {
        int factor = 2;
        int suma = 0;
        for (int i = numeros.length() - 1; i >= 0; i--) {
            int d = numeros.charAt(i) - '0';
            if (d < 0 || d > 9) {
                throw new IllegalArgumentException("Carácter no numérico en clave: " + numeros);
            }
            suma += d * factor;
            factor++;
            if (factor > 7) factor = 2;
        }
        int mod = suma % 11;
        int dv = 11 - mod;
        if (dv == 11) dv = 0;
        if (dv == 10) dv = 1;
        return (char) ('0' + dv);
    }

    /**
     * 8 dígitos aleatorios criptográficamente seguros — el SRI no le exige significado.
     */
    public static String generarCodigoNumericoAleatorio() {
        // SecureRandom.nextInt(100_000_000) puede dar valores < 10_000_000 → padding con ceros
        int n = RNG.nextInt(100_000_000);
        return String.format("%08d", n);
    }

    private static void validarRuc(String ruc) {
        if (ruc == null || !ruc.matches("\\d{13}")) {
            throw new IllegalArgumentException("RUC debe ser 13 dígitos");
        }
    }

    private static void validarSerie(String s, String campo) {
        if (s == null || !s.matches("\\d{3}")) {
            throw new IllegalArgumentException(campo + " debe ser 3 dígitos");
        }
    }
}
