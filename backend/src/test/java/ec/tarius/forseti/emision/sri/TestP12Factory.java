package ec.tarius.forseti.emision.sri;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Genera un .p12 self-signed en memoria para tests. No es un cert válido para SRI real;
 * sirve solo para verificar el pipeline de firma (xades4j puede leerlo, sign() produce
 * una Signature, verify() valida la firma).
 */
public final class TestP12Factory {

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private TestP12Factory() {}

    public record P12 (byte[] bytes, String password, X509Certificate cert) {}

    public static P12 generar() {
        return generar("CN=Forseti Test Cert, O=TARIUS S.A.S, C=EC", "test1234");
    }

    public static P12 generar(String dn, String password) {
        try {
            // 1. Par RSA 2048 (mínimo que el SRI acepta)
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();

            // 2. Certificado self-signed válido por 1 año
            X500Name subject = new X500Name(dn);
            Date desde = Date.from(Instant.now().minus(1, ChronoUnit.DAYS));
            Date hasta = Date.from(Instant.now().plus(365, ChronoUnit.DAYS));

            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(System.currentTimeMillis()),
                desde, hasta, subject, kp.getPublic()
            );

            ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                .setProvider("BC").build(kp.getPrivate());

            X509Certificate cert = new JcaX509CertificateConverter()
                .setProvider("BC").getCertificate(builder.build(signer));

            // 3. Empaquetar en PKCS12 (provider BC para compatibilidad con readers SRI-style)
            KeyStore ks = KeyStore.getInstance("PKCS12", "BC");
            ks.load(null, null);
            ks.setKeyEntry("forseti-test", kp.getPrivate(), password.toCharArray(),
                new Certificate[]{cert});

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ks.store(out, password.toCharArray());

            return new P12(out.toByteArray(), password, cert);
        } catch (Exception e) {
            throw new RuntimeException("No se pudo generar .p12 de test", e);
        }
    }
}
