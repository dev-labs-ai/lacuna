package com.lacuna.support;

import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * Does in Java what Web PKI does in the browser: reads the signer's certificate and signs hashes with its key. Uses
 * Lacuna's public test certificate "Pierre de Fermat", accepted when trusting Lacuna's test root.
 */
public final class TestSigner {

    public static final String NAME = "Pierre de Fermat";

    // DER prefix of an RSA PKCS#1 v1.5 DigestInfo for SHA-256 (RFC 8017, section 9.2).
    private static final byte[] SHA256_DIGEST_INFO_PREFIX = HexFormat.of().parseHex("3031300d060960864801650304020105000420");

    private final PrivateKey privateKey;
    private final X509Certificate certificate;

    private TestSigner(PrivateKey privateKey, X509Certificate certificate) {
        this.privateKey = privateKey;
        this.certificate = certificate;
    }

    public static TestSigner pierreDeFermat() throws Exception {
        var password = "1234".toCharArray();
        var keyStore = KeyStore.getInstance("PKCS12");
        try (var in = new ClassPathResource("pierre-de-fermat.pfx").getInputStream()) {
            keyStore.load(in, password);
        }
        for (var alias : Collections.list(keyStore.aliases())) {
            if (keyStore.isKeyEntry(alias)) {
                return new TestSigner(
                        (PrivateKey) keyStore.getKey(alias, password),
                        (X509Certificate) keyStore.getCertificate(alias));
            }
        }
        throw new IllegalStateException("No key entry in pierre-de-fermat.pfx");
    }

    /**
     * For {@code @EnabledIf}: tests driving PKI Express need its {@code pkie} executable on the PATH.
     */
    public static boolean pkiExpressInstalled() {
        return Stream.of(System.getenv("PATH").split(File.pathSeparator))
                .anyMatch(dir -> Files.isExecutable(Path.of(dir, "pkie")));
    }

    /**
     * Waits for the clock to reach the next second. A CAdES signing time has a one-second resolution, so the same
     * signer signing the same content twice within one second produces the very same signature, which PKI Express
     * does not add again. Call it between co-signatures of the same content by this signer.
     */
    public static void awaitNextSecond() throws InterruptedException {
        Thread.sleep(1000 - System.currentTimeMillis() % 1000 + 10);
    }

    /**
     * Equivalent of Web PKI's readCertificate().
     */
    public String certificateBase64() throws GeneralSecurityException {
        return Base64.getEncoder().encodeToString(certificate.getEncoded());
    }

    /**
     * Equivalent of Web PKI's signHash() for a SHA-256 hash.
     */
    public String sign(String hashBase64) throws GeneralSecurityException {
        var signature = Signature.getInstance("NONEwithRSA");
        signature.initSign(privateKey);
        signature.update(SHA256_DIGEST_INFO_PREFIX);
        signature.update(Base64.getDecoder().decode(hashBase64));
        return Base64.getEncoder().encodeToString(signature.sign());
    }
}
