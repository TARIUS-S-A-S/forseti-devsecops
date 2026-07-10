package ec.tarius.forseti.emision.sri;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import xades4j.providers.KeyingDataProvider;
import xades4j.providers.SigningCertChainException;
import xades4j.providers.SigningKeyException;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * KeyingDataProvider para xades4j que carga la clave privada y el certificado desde
 * BYTES de un archivo .p12 ya en memoria (no toca disco).
 *
 * Sprint 3: el .p12 viene de CertificadoService.descifrarParaFirmar(), que lo descifra
 * con AES-256-GCM justo antes de firmar (ADR-6). No persiste en disco, no se loguea.
 *
 * Usa BouncyCastle como provider PKCS12 porque los .p12 emitidos por las ACs
 * ecuatorianas (Security Data, ANF AC, BCE) usan variantes legacy (RC2-40 + SHA1)
 * que el SunJSSE rechaza por default.
 */
public class InMemoryKeyingDataProvider implements KeyingDataProvider {

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final PrivateKey privateKey;
    private final List<X509Certificate> chain;

    public InMemoryKeyingDataProvider(byte[] p12Bytes, String password) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12", "BC");
            ks.load(new ByteArrayInputStream(p12Bytes), password.toCharArray());

            // Buscar el primer alias que tiene clave privada
            String alias = null;
            for (Enumeration<String> a = ks.aliases(); a.hasMoreElements();) {
                String name = a.nextElement();
                if (ks.isKeyEntry(name)) {
                    alias = name;
                    break;
                }
            }
            if (alias == null) {
                throw new IllegalArgumentException(
                    "El .p12 no contiene una entrada con clave privada");
            }

            this.privateKey = (PrivateKey) ks.getKey(alias, password.toCharArray());
            Certificate[] certs = ks.getCertificateChain(alias);
            this.chain = new ArrayList<>(certs.length);
            for (Certificate c : certs) {
                this.chain.add((X509Certificate) c);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "No se pudo abrir el .p12 (contraseña incorrecta o archivo corrupto): "
                + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public List<X509Certificate> getSigningCertificateChain() throws SigningCertChainException {
        return chain;
    }

    @Override
    public PrivateKey getSigningKey(X509Certificate signingCert) throws SigningKeyException {
        return privateKey;
    }

    public X509Certificate certificadoFirmante() {
        return chain.isEmpty() ? null : chain.get(0);
    }
}
