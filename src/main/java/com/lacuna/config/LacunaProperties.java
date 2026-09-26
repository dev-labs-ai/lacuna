package com.lacuna.config;

import com.lacunasoftware.pkiexpress.StandardSignaturePolicies;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

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
     * @param timeZone             IANA time zone for the signing time printed on the visual representation
     */
    public record PkiExpress(
            Path home,
            @DefaultValue("false") boolean trustLacunaTestRoot,
            @DefaultValue List<Path> trustedRoots,
            @DefaultValue("false") boolean offline,
            @DefaultValue("PadesBasicWithLTV") StandardSignaturePolicies padesPolicy,
            @DefaultValue("PkiBrazilCadesAdrBasica") StandardSignaturePolicies cadesPolicy,
            @DefaultValue("pt-BR") String culture,
            @DefaultValue("America/Sao_Paulo") String timeZone) {
    }

    /**
     * @param license Web PKI license (binary Base64 or JSON). Without one, Web PKI only works on localhost.
     */
    public record WebPki(String license) {
    }

    /**
     * @param dir folder where uploaded and signed documents, PKI Express temp files and transfer files are kept;
     *            defaults to {@code <java.io.tmpdir>/lacuna}
     */
    public record Storage(Path dir) {

        public Storage {
            if (dir == null) {
                dir = Path.of(System.getProperty("java.io.tmpdir"), "lacuna");
            }
        }
    }
}
