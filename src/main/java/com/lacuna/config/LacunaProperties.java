package com.lacuna.config;

import com.lacunasoftware.pkiexpress.StandardSignaturePolicies;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

@ConfigurationProperties("lacuna")
public record LacunaProperties(
        @DefaultValue PkiExpress pkiExpress,
        @DefaultValue Signature signature,
        @DefaultValue WebPki webPki,
        @DefaultValue Storage storage) {

    /**
     * @param validateCertificateOnSelection validate the chosen certificate before asking the user to sign, instead of
     *                                       learning only on completion that PKI Express rejects it
     */
    public record Signature(@DefaultValue("true") boolean validateCertificateOnSelection) {
    }

    /**
     * @param home                 folder containing the {@code pkie} executable; when empty, {@code pkie} is resolved
     *                             from the PATH on Linux or from the standard install folders on Windows
     * @param trustLacunaTestRoot  accept certificates issued by Lacuna's test PKI. Development only!
     * @param trustedRoots         extra root certificates (.cer files) to trust
     * @param offline              run PKI Express without network access (no CRL/OCSP fetching)
     * @param padesPolicy          policy of PAdES signatures; PKI Express' default is PAdES basic with LTV
     * @param cadesPolicy          policy of CAdES signatures; PKI Express' default is ICP-Brasil AD-RB
     * @param culture              culture for messages and the signing time printed on the visual representation
     */
    public record PkiExpress(
            Path home,
            @DefaultValue("false") boolean trustLacunaTestRoot,
            @DefaultValue List<Path> trustedRoots,
            @DefaultValue("false") boolean offline,
            @DefaultValue("PadesBasicWithLTV") StandardSignaturePolicies padesPolicy,
            @DefaultValue("PkiBrazilCadesAdrBasica") StandardSignaturePolicies cadesPolicy,
            @DefaultValue("pt-BR") String culture) {
    }

    /**
     * @param license Web PKI license (binary Base64 or JSON). Without one, Web PKI only works on localhost.
     */
    public record WebPki(String license) {
    }

    /**
     * Documents are objects in an S3 bucket, described by a row in the database.
     *
     * @param dir    local working folder: PKI Express temp and transfer files, and copies of documents while PKI Express
     *               works on them; defaults to {@code <java.io.tmpdir>/lacuna}
     * @param bucket S3 bucket of the documents, created on startup if missing
     */
    public record Storage(Path dir, @DefaultValue("documents") String bucket, @DefaultValue S3 s3) {

        public Storage {
            if (dir == null) {
                dir = Path.of(System.getProperty("java.io.tmpdir"), "lacuna");
            }
        }
    }

    /**
     * @param endpoint  URL of an S3-compatible server such as MinIO, addressed path-style; when empty, AWS S3
     * @param accessKey when empty, credentials come from the AWS default chain (environment, profile, role)
     */
    public record S3(URI endpoint, @DefaultValue("us-east-1") String region, String accessKey, String secretKey) {
    }
}
