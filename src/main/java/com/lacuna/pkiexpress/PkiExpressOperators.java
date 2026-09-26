package com.lacuna.pkiexpress;

import com.lacuna.config.LacunaProperties;
import com.lacunasoftware.pkiexpress.CadesSignatureExplorer;
import com.lacunasoftware.pkiexpress.CadesSignatureStarter;
import com.lacunasoftware.pkiexpress.CertificateExplorer;
import com.lacunasoftware.pkiexpress.PadesSignatureExplorer;
import com.lacunasoftware.pkiexpress.PadesSignatureStarter;
import com.lacunasoftware.pkiexpress.PkiExpressConfig;
import com.lacunasoftware.pkiexpress.PkiExpressOperator;
import com.lacunasoftware.pkiexpress.SignatureFinisher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates PKI Express operators configured from {@link LacunaProperties.PkiExpress}. Each operator serves a single
 * operation; run it with {@link #execute} so its temporary files are disposed of.
 */
@Component
public class PkiExpressOperators {

    /**
     * Time zone of the dates PKI Express writes, such as the signing time printed on PDFs: UTC, since a PDF cannot
     * know where it will be read. Web pages show signing times in the reader's time zone instead.
     */
    public static final String TIME_ZONE = "UTC";

    private final LacunaProperties.PkiExpress settings;
    private final PkiExpressConfig config;

    public PkiExpressOperators(LacunaProperties properties) throws IOException {
        this.settings = properties.pkiExpress();
        var storageDir = properties.storage().dir();
        this.config = new PkiExpressConfig(
                settings.home(),
                Files.createDirectories(storageDir.resolve("pkie-temp")),
                Files.createDirectories(storageDir.resolve("pkie-transfer")));
    }

    public PadesSignatureStarter padesSignatureStarter() {
        var starter = configure(new PadesSignatureStarter(config));
        starter.setSignaturePolicy(settings.padesPolicy());
        return starter;
    }

    public CadesSignatureStarter cadesSignatureStarter() {
        var starter = configure(new CadesSignatureStarter(config));
        starter.setSignaturePolicy(settings.cadesPolicy());
        return starter;
    }

    public SignatureFinisher signatureFinisher() {
        return configure(new SignatureFinisher(config));
    }

    public PadesSignatureExplorer padesSignatureExplorer() {
        return configure(new PadesSignatureExplorer(config));
    }

    public CadesSignatureExplorer cadesSignatureExplorer() {
        return configure(new CadesSignatureExplorer(config));
    }

    public CertificateExplorer certificateExplorer() {
        return configure(new CertificateExplorer(config));
    }

    /**
     * Where a signature starter left the transfer file with the given id.
     */
    public Path transferFile(String transferFileId) {
        return config.getTransferDataFolder().resolve(transferFileId);
    }

    /**
     * Runs an operation and disposes of the operator's temporary files, reporting failures as
     * {@link PkiExpressException}.
     */
    public <O extends PkiExpressOperator, R> R execute(O operator, Operation<O, R> operation) throws IOException {
        try {
            return operation.apply(operator);
        } catch (RuntimeException e) {
            throw PkiExpressException.from(e);
        } finally {
            operator.dispose();
        }
    }

    private <T extends PkiExpressOperator> T configure(T operator) {
        // Never enable in production: it makes anyone holding a Lacuna test certificate a trusted signer.
        operator.setTrustLacunaTestRoot(settings.trustLacunaTestRoot());
        operator.setOffline(settings.offline());
        settings.trustedRoots().forEach(operator::addTrustedRoot);
        if (StringUtils.hasText(settings.culture())) {
            operator.setCulture(settings.culture());
        }
        operator.setTimeZone(TIME_ZONE);
        return operator;
    }

    @FunctionalInterface
    public interface Operation<O, R> {

        R apply(O operator) throws IOException;
    }
}
