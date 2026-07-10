package ec.tarius.forseti.empresa;

import ec.tarius.forseti.emision.ComprobanteRepository;
import ec.tarius.forseti.empresa.domain.CertificadoFirma;
import ec.tarius.forseti.shared.audit.AuditLogger;
import ec.tarius.forseti.shared.crypto.CryptoBoxService;
import ec.tarius.forseti.shared.errors.AppException;
import ec.tarius.forseti.shared.errors.ErrorCode;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Enumeration;
import java.util.Optional;
import java.util.UUID;

/**
 * Maneja la carga, custodia y consulta de certificados de firma electrónica (.p12).
 *
 * Reglas ADR-6 / RL-6:
 *   - El .p12 viene del cliente vía multipart. La contraseña viene en el body.
 *   - Se valida cargando el keystore con BouncyCastle (soporta PKCS12 viejos del SRI).
 *   - Se extrae la metadata (sujeto, emisor, vigencia) para mostrar en UI.
 *   - El blob completo + la contraseña se cifran AES-256-GCM (CryptoBoxService).
 *   - NUNCA se persiste el blob en plano; NUNCA viaja al front; NUNCA va al log.
 *   - Si el certificado está caducado → rechazo.
 */
@Service
public class CertificadoService {

    private static final Logger log = LoggerFactory.getLogger(CertificadoService.class);

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final CertificadoFirmaRepository certRepo;
    private final ComprobanteRepository comprobanteRepo;
    private final CryptoBoxService crypto;
    private final AuditLogger audit;

    public CertificadoService(CertificadoFirmaRepository certRepo,
                               ComprobanteRepository comprobanteRepo,
                               CryptoBoxService crypto,
                               AuditLogger audit) {
        this.certRepo = certRepo;
        this.comprobanteRepo = comprobanteRepo;
        this.crypto = crypto;
        this.audit = audit;
    }

    /**
     * Carga y persiste un certificado para la empresa. Marca como activo y desactiva el anterior.
     */
    @Transactional
    public CertificadoFirma cargar(UUID empresaId, byte[] p12Bytes, String password, UUID usuarioId) {
        if (p12Bytes == null || p12Bytes.length == 0) {
            throw new AppException(ErrorCode.CERTIFICADO_INVALIDO, "Archivo .p12 vacío");
        }
        if (password == null || password.isEmpty()) {
            throw new AppException(ErrorCode.CERTIFICADO_INVALIDO, "La contraseña del .p12 es obligatoria");
        }

        Metadata meta = extraerMetadata(p12Bytes, password);

        if (meta.vigenteHasta.isBefore(Instant.now())) {
            throw new AppException(ErrorCode.CERTIFICADO_CADUCADO,
                "El certificado está caducado (vigente hasta " + meta.vigenteHasta + ")");
        }

        // Desactivar el anterior (si existe)
        certRepo.findActivo(empresaId).ifPresent(prev -> {
            prev.desactivar();
            certRepo.save(prev);
        });

        byte[] p12Cifrado = crypto.cifrar(p12Bytes);
        byte[] passwordCifrada = crypto.cifrar(password.getBytes(StandardCharsets.UTF_8));

        CertificadoFirma cert = CertificadoFirma.nuevo(
            empresaId, p12Cifrado, passwordCifrada,
            meta.sujetoCn, meta.emisorCn, meta.numeroSerie,
            meta.vigenteDesde, meta.vigenteHasta,
            usuarioId);
        certRepo.save(cert);

        // IMPORTANTE: no loguear bytes ni password — solo metadata
        log.info("Certificado cargado para empresa_id={} sujeto='{}' vigente_hasta={}",
            empresaId, meta.sujetoCn, meta.vigenteHasta);
        audit.log("certificado_cargado", "certificado_firma", cert.getId(),
            "empresa_id=" + empresaId + " sujeto_cn=" + meta.sujetoCn
            + " vigente_hasta=" + meta.vigenteHasta);
        return cert;
    }

    @Transactional(readOnly = true)
    public Optional<CertificadoFirma> activo(UUID empresaId) {
        return certRepo.findActivo(empresaId);
    }

    /**
     * Historial COMPLETO de certs de la empresa (activos + desactivados), del más reciente
     * al más viejo. Sirve para que el usuario vea qué certs estuvieron vigentes cuándo —
     * útil al renovar la firma del proveedor o al auditar facturas viejas.
     *
     * No incluye el blob ni la password (CertificadoFirma los expone como @JsonIgnore).
     */
    @Transactional(readOnly = true)
    public java.util.List<CertificadoFirma> historial(UUID empresaId) {
        return certRepo.findHistorial(empresaId);
    }

    /**
     * Desactiva el cert activo de la empresa SIN cargar uno nuevo. Útil cuando el usuario
     * quiere parar la emisión (por ejemplo, robo del .p12, cambio de proveedor pendiente,
     * baja temporal de la empresa).
     *
     * NO borra el registro — solo lo marca {@code activo=false}. Esto preserva las
     * referencias históricas (qué cert firmó qué factura) requerido por el RNF de
     * trazabilidad. Borrar definitivamente requeriría borrar también los comprobantes
     * que firmó, y eso es ilegal.
     *
     * Si no hay cert activo, no-op (idempotente).
     */
    @Transactional
    public void desactivar(UUID empresaId) {
        certRepo.findActivo(empresaId).ifPresent(cert -> {
            cert.desactivar();
            certRepo.save(cert);
            log.info("Certificado desactivado para empresa_id={} sujeto='{}'",
                empresaId, cert.getSujetoCn());
            audit.log("certificado_desactivado", "certificado_firma", cert.getId(),
                "empresa_id=" + empresaId + " sujeto_cn=" + cert.getSujetoCn());
        });
    }

    /**
     * Promueve un cert inactivo a activo. Si hay otro activo, lo desactiva en la misma TX.
     * Rechaza si el cert no pertenece a la empresa (defensa multi-tenant, RLS ya lo bloquea
     * pero validamos por mensaje claro) o si está caducado.
     */
    @Transactional
    public CertificadoFirma activar(UUID empresaId, UUID certificadoId) {
        CertificadoFirma cert = certRepo.findById(certificadoId)
            .orElseThrow(() -> new AppException(ErrorCode.CERTIFICADO_NO_ENCONTRADO,
                "Certificado no encontrado"));
        if (!cert.getEmpresaId().equals(empresaId)) {
            throw new AppException(ErrorCode.CERTIFICADO_NO_ENCONTRADO,
                "Certificado no encontrado");
        }
        if (cert.getVigenteHasta().isBefore(Instant.now())) {
            throw new AppException(ErrorCode.CERTIFICADO_CADUCADO,
                "El certificado está caducado (vigente hasta " + cert.getVigenteHasta() + ")");
        }

        // Desactivar el activo actual (si es distinto al que queremos promover)
        certRepo.findActivo(empresaId).ifPresent(prev -> {
            if (!prev.getId().equals(certificadoId)) {
                prev.desactivar();
                certRepo.save(prev);
            }
        });

        if (!cert.isActivo()) {
            cert.activar();
            certRepo.save(cert);
        }
        log.info("Certificado activado para empresa_id={} sujeto='{}' id={}",
            empresaId, cert.getSujetoCn(), cert.getId());
        audit.log("certificado_activado", "certificado_firma", cert.getId(),
            "empresa_id=" + empresaId + " sujeto_cn=" + cert.getSujetoCn());
        return cert;
    }

    /**
     * Eliminación definitiva. Permitida SOLO si ningún comprobante referencia este cert
     * (preserva trazabilidad legal — si el cert firmó algo, queda en BD como Inactivo).
     *
     * @throws AppException con {@link ErrorCode#CERTIFICADO_TIENE_COMPROBANTES} si tiene
     *         comprobantes referenciándolo. La UI debe mostrar "no se puede eliminar,
     *         solo desactivar".
     */
    @Transactional
    public void eliminar(UUID empresaId, UUID certificadoId) {
        CertificadoFirma cert = certRepo.findById(certificadoId)
            .orElseThrow(() -> new AppException(ErrorCode.CERTIFICADO_NO_ENCONTRADO,
                "Certificado no encontrado"));
        if (!cert.getEmpresaId().equals(empresaId)) {
            throw new AppException(ErrorCode.CERTIFICADO_NO_ENCONTRADO,
                "Certificado no encontrado");
        }
        long comprobantes = comprobanteRepo.countByCertificadoId(certificadoId);
        if (comprobantes > 0) {
            throw new AppException(ErrorCode.CERTIFICADO_TIENE_COMPROBANTES,
                "Este certificado firmó " + comprobantes + " comprobante"
                + (comprobantes == 1 ? "" : "s")
                + " — no se puede eliminar (preserva trazabilidad legal). "
                + "Solo se puede desactivar.");
        }
        certRepo.delete(cert);
        log.info("Certificado eliminado definitivamente — empresa_id={} sujeto='{}' id={}",
            empresaId, cert.getSujetoCn(), certificadoId);
        audit.log("certificado_eliminado", "certificado_firma", certificadoId,
            "empresa_id=" + empresaId + " sujeto_cn=" + cert.getSujetoCn());
    }

    /**
     * Solo el servicio firmador (Sprint 3+) llama a esto: descifra el .p12 y su password en memoria.
     * NO devuelve nunca al controller HTTP.
     */
    public DescifradoEnMemoria descifrarParaFirmar(UUID certificadoId) {
        CertificadoFirma c = certRepo.findById(certificadoId)
            .orElseThrow(() -> new AppException(ErrorCode.CERTIFICADO_INVALIDO, "Certificado no encontrado"));
        byte[] p12 = crypto.descifrar(c.getP12Cifrado());
        byte[] pwd = crypto.descifrar(c.getPasswordCifrada());
        return new DescifradoEnMemoria(p12, new String(pwd, StandardCharsets.UTF_8));
    }

    // ─────────────────────────────────────────────────────────────
    // Metadata extraction
    // ─────────────────────────────────────────────────────────────

    private Metadata extraerMetadata(byte[] p12Bytes, String password) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12", "BC");
            ks.load(new ByteArrayInputStream(p12Bytes), password.toCharArray());

            Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (ks.isKeyEntry(alias)) {
                    java.security.cert.Certificate c = ks.getCertificate(alias);
                    if (c instanceof X509Certificate x) {
                        return new Metadata(
                            shortName(x.getSubjectX500Principal().getName()),
                            shortName(x.getIssuerX500Principal().getName()),
                            x.getSerialNumber().toString(),
                            x.getNotBefore().toInstant(),
                            x.getNotAfter().toInstant());
                    }
                }
            }
            throw new AppException(ErrorCode.CERTIFICADO_INVALIDO,
                "El .p12 no contiene una clave privada con certificado X.509");
        } catch (AppException e) {
            throw e;
        } catch (java.io.IOException e) {
            // Password incorrecta (PKCS12 con BC lanza IOException con BadPaddingException dentro)
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("mac check failed")) {
                throw new AppException(ErrorCode.CERTIFICADO_INVALIDO,
                    "Contraseña del certificado incorrecta");
            }
            throw new AppException(ErrorCode.CERTIFICADO_INVALIDO,
                "Archivo .p12 inválido o contraseña incorrecta");
        } catch (Exception e) {
            log.warn("Error leyendo .p12: {}", e.getClass().getSimpleName());
            throw new AppException(ErrorCode.CERTIFICADO_INVALIDO,
                "No se pudo leer el archivo .p12");
        }
    }

    private static String shortName(String x500) {
        if (x500 == null) return null;
        // Extrae CN= si está presente; si no, devuelve el DN entero
        for (String part : x500.split(",")) {
            String p = part.trim();
            if (p.startsWith("CN=")) return p.substring(3);
        }
        return x500;
    }

    private record Metadata(String sujetoCn, String emisorCn, String numeroSerie,
                             Instant vigenteDesde, Instant vigenteHasta) {}

    public record DescifradoEnMemoria(byte[] p12, String password) {}
}
