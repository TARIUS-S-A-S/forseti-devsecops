package ec.tarius.forseti.shared.validacion;

/**
 * Validador de RUC ecuatoriano (13 dígitos).
 * Formato: 10 dígitos (cédula o RUC sociedad) + "001" final.
 * Tercer dígito:
 *   - 0..5  → persona natural (los primeros 10 dígitos = cédula)
 *   - 6      → entidad pública
 *   - 9      → sociedad privada
 *
 * Algoritmo del dígito verificador depende del tipo (módulo 10 para PN, módulo 11 para sociedades).
 * Implementación según SRI.
 */
public final class RucValidator {

    private RucValidator() {}

    /**
     * Valida un RUC ecuatoriano contra el formato Y el dígito verificador del algoritmo SRI.
     * Estricto: si el dígito verificador no matchea, rechaza.
     */
    public static boolean esValido(String ruc) {
        return formatoValido(ruc) && digitoVerificadorValido(ruc);
    }

    /**
     * Valida SOLO el formato (13 dígitos numéricos, provincia 1-24, terminado en 001).
     * Útil cuando se confía en que el SRI rechazará si el dígito verificador está mal.
     * Hay RUCs reales que pasan SRI pero no el algoritmo público del checkdigit —
     * por eso esta versión laxa existe.
     */
    public static boolean formatoValido(String ruc) {
        if (ruc == null || ruc.length() != 13) return false;
        if (!ruc.matches("[0-9]{13}")) return false;
        if (!"001".equals(ruc.substring(10))) return false;
        int provincia = Integer.parseInt(ruc.substring(0, 2));
        return provincia >= 1 && provincia <= 24;
    }

    public static boolean digitoVerificadorValido(String ruc) {
        if (!formatoValido(ruc)) return false;
        char tercer = ruc.charAt(2);
        if (tercer >= '0' && tercer <= '5') {
            return validarCedulaPN(ruc.substring(0, 10));
        } else if (tercer == '6') {
            return validarPublica(ruc.substring(0, 9));
        } else if (tercer == '9') {
            return validarPrivada(ruc.substring(0, 10));
        }
        return false;
    }

    /** Persona natural: cédula de 10 dígitos con módulo 10 (algoritmo Luhn modificado). */
    private static boolean validarCedulaPN(String cedula) {
        int[] coef = {2, 1, 2, 1, 2, 1, 2, 1, 2};
        int suma = 0;
        for (int i = 0; i < 9; i++) {
            int prod = (cedula.charAt(i) - '0') * coef[i];
            if (prod >= 10) prod -= 9;
            suma += prod;
        }
        int verificador = (10 - (suma % 10)) % 10;
        return verificador == (cedula.charAt(9) - '0');
    }

    /** Pública: 9 dígitos, módulo 11, coeficientes 3,2,7,6,5,4,3,2. ver=10 → 0 (convención SRI). */
    private static boolean validarPublica(String base) {
        int[] coef = {3, 2, 7, 6, 5, 4, 3, 2};
        int suma = 0;
        for (int i = 0; i < 8; i++) suma += (base.charAt(i) - '0') * coef[i];
        int mod = suma % 11;
        int verificador = mod == 0 ? 0 : 11 - mod;
        if (verificador == 10) verificador = 0;
        return verificador == (base.charAt(8) - '0');
    }

    /** Privada/jurídica: 10 dígitos, módulo 11, coeficientes 4,3,2,7,6,5,4,3,2. */
    private static boolean validarPrivada(String base) {
        int[] coef = {4, 3, 2, 7, 6, 5, 4, 3, 2};
        int suma = 0;
        for (int i = 0; i < 9; i++) suma += (base.charAt(i) - '0') * coef[i];
        int mod = suma % 11;
        int verificador = mod == 0 ? 0 : 11 - mod;
        if (verificador == 10) return false;
        return verificador == (base.charAt(9) - '0');
    }
}
