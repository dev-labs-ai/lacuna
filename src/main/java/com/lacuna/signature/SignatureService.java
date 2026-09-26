package com.lacuna.signature;

import com.lacuna.config.LacunaProperties;
import com.lacuna.document.DocumentFormat;
import com.lacuna.document.DocumentStorage;
import com.lacuna.document.InvalidDocumentException;
import com.lacuna.document.StoredDocument;
import com.lacuna.pkiexpress.PkiExpressException;
import com.lacuna.pkiexpress.PkiExpressOperators;
import com.lacunasoftware.pkiexpress.CadesSignerInfo;
import com.lacunasoftware.pkiexpress.PKCertificate;
import com.lacunasoftware.pkiexpress.PadesSize;
import com.lacunasoftware.pkiexpress.PadesVisualAutoPositioning;
import com.lacunasoftware.pkiexpress.PadesVisualRectangle;
import com.lacunasoftware.pkiexpress.PadesVisualRepresentation;
import com.lacunasoftware.pkiexpress.PadesVisualText;
import com.lacunasoftware.pkiexpress.SignatureStartResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * PAdES and CAdES signatures with PKI Express, where the signer's private key never leaves the user's machine:
 * <ol>
 *     <li>{@link #start}: PKI Express prepares the signature and returns the hash to be signed;</li>
 *     <li>the browser signs that hash with Web PKI;</li>
 *     <li>{@link #complete}: PKI Express embeds the signature value, producing the signed file.</li>
 * </ol>
 */
@Service
public class SignatureService {

    // PKI Express names transfer files with 16 random bytes in hex.
    private static final Pattern TRANSFER_FILE_ID = Pattern.compile("[0-9a-f]{32}");

    private final PkiExpressOperators pkiExpress;
    private final CertificateValidator certificates;
    private final DocumentStorage storage;
    private final SignatureRepository repository;
    private final LacunaProperties.PkiExpress settings;
    private final boolean validateCertificateOnSelection;

    SignatureService(PkiExpressOperators pkiExpress, CertificateValidator certificates, DocumentStorage storage,
                     SignatureRepository repository, LacunaProperties properties) {
        this.pkiExpress = pkiExpress;
        this.certificates = certificates;
        this.storage = storage;
        this.repository = repository;
        this.settings = properties.pkiExpress();
        this.validateCertificateOnSelection = properties.signature().validateCertificateOnSelection();
    }

    /**
     * Validates the certificate the user chose, before asking them to sign, unless
     * {@code lacuna.signature.validate-certificate-on-selection} is off. PKI Express only validates it on completion,
     * after the user has signed (and maybe typed a PIN) for nothing.
     *
     * @throws CertificateRejectedException if the certificate is not accepted
     */
    public void checkCertificate(String certificateBase64) throws IOException {
        if (validateCertificateOnSelection) {
            certificates.requireValid(certificateBase64);
        }
    }

    /**
     * @throws InvalidDocumentException if the document cannot be signed in this format
     */
    public SignatureStart start(StoredDocument document, SignatureFormat format, String certificateBase64)
            throws IOException {
        if (!format.supports(document.format())) {
            throw new InvalidDocumentException("Somente arquivos PDF podem receber assinaturas PAdES.");
        }
        try (var file = storage.copyToWorkFolder(document)) {
            return start(file.path(), format, certificateBase64);
        }
    }

    /**
     * Completes the signature of a stored document, storing the signed file as a new document and recording the
     * signature: which document was signed, the one it produced, and the signer.
     */
    public SignedDocument complete(StoredDocument document, String transferFileId, String signature,
                                   String certificateBase64) throws IOException {
        try (var file = storage.copyToWorkFolder(document); var output = storage.newWorkFile()) {
            var signer = complete(file.path(), transferFileId, signature, certificateBase64, output.path());
            var signedFormat = DocumentFormat.detect(output.path());
            var format = SignatureFormat.producing(signedFormat);
            var signedAt = signingTime(output.path(), signedFormat);
            var signed = storage.storeSigned(output.path(), document.signedName(signedFormat), document, stored ->
                    repository.insert(StoredSignature.of(document, stored, format, policy(format), signedAt, signer)));
            return new SignedDocument(signed, signer.getSubjectName().getCommonName());
        }
    }

    /**
     * @param certificateBase64 the signer's certificate (DER, Base64), as read by Web PKI
     */
    public SignatureStart start(Path file, SignatureFormat format, String certificateBase64) throws IOException {
        var result = switch (format) {
            case PADES -> pkiExpress.execute(pkiExpress.padesSignatureStarter(), starter -> {
                starter.setPdfToSign(file);
                starter.setVisualRepresentation(visualRepresentation());
                starter.setCertificateBase64(certificateBase64);
                return starter.start();
            });
            case CADES -> pkiExpress.execute(pkiExpress.cadesSignatureStarter(), starter -> {
                // PKI Express co-signs the file when it is already a CAdES signature.
                starter.setFileToSign(file);
                starter.setEncapsulateContent(true);
                starter.setCertificateBase64(certificateBase64);
                return starter.start();
            });
        };
        return toSignatureStart(result);
    }

    /**
     * Writes the signed file to {@code output}: a PDF for PAdES, a .p7s for CAdES.
     *
     * @param file              the same file given to {@link #start}
     * @param transferFileId    returned by {@link #start}
     * @param signature         the hash signed by Web PKI (Base64)
     * @param certificateBase64 the certificate given to {@link #start}, used to explain a rejection
     * @return the signer's certificate
     * @throws CertificateRejectedException if PKI Express refused the signature because of the certificate
     */
    public PKCertificate complete(Path file, String transferFileId, String signature, String certificateBase64,
                                  Path output) throws IOException {
        if (!TRANSFER_FILE_ID.matcher(transferFileId).matches()) {
            throw new PkiExpressException("Dados da assinatura inválidos. Reinicie o processo de assinatura.");
        }
        var transferFile = pkiExpress.transferFile(transferFileId);
        if (!Files.exists(transferFile)) {
            throw new PkiExpressException("Esta assinatura já foi concluída ou não existe mais. Reinicie o processo de assinatura.");
        }
        try {
            return pkiExpress.execute(pkiExpress.signatureFinisher(), finisher -> {
                finisher.setFileToSign(file);
                finisher.setTransferFileId(transferFileId);
                finisher.setSignature(signature);
                finisher.setOutputFilePath(output);
                return finisher.complete(true);
            });
        } catch (PkiExpressException e) {
            Files.deleteIfExists(output);
            throw explain(e, certificateBase64);
        } finally {
            // A transfer file serves a single completion, successful or not.
            Files.deleteIfExists(transferFile);
        }
    }

    /**
     * PKI Express reports a rejected certificate as its raw validation output. When the certificate is the cause,
     * report it as a {@link CertificateRejectedException}, which says why in plain words.
     */
    private PkiExpressException explain(PkiExpressException failure, String certificateBase64) {
        try {
            certificates.requireValid(certificateBase64);
        } catch (CertificateRejectedException rejected) {
            rejected.addSuppressed(failure);
            return rejected;
        } catch (PkiExpressException | IOException e) {
            failure.addSuppressed(e);
        }
        return failure;
    }

    /**
     * When the newest signature in a file was made, as recorded in the file (the PDF signature's /M entry, the CAdES
     * signing-time attribute): that of the signature just added, since signing again only adds signatures.
     */
    private Instant signingTime(Path signedFile, DocumentFormat format) throws IOException {
        List<? extends CadesSignerInfo> signers = switch (format) {
            case PDF -> pkiExpress.execute(pkiExpress.padesSignatureExplorer(), explorer -> {
                explorer.setSignatureFile(signedFile);
                return explorer.open().getSigners();
            });
            case CMS -> pkiExpress.execute(pkiExpress.cadesSignatureExplorer(), explorer -> {
                explorer.setSignatureFile(signedFile);
                return explorer.open().getSigners();
            });
            case OTHER -> List.of();
        };
        return signers.stream()
                .map(CadesSignerInfo::getSigningTime)
                .filter(Objects::nonNull)
                .map(Date::toInstant)
                .max(Comparator.naturalOrder())
                // A signature policy may leave the signing time out; the completion time is then the closest.
                .orElseGet(Instant::now);
    }

    // The policy PkiExpressOperators gives the starter of this format.
    private String policy(SignatureFormat format) {
        return switch (format) {
            case PADES -> settings.padesPolicy().name();
            case CADES -> settings.cadesPolicy().name();
        };
    }

    private static SignatureStart toSignatureStart(SignatureStartResult result) {
        return new SignatureStart(result.getToSignHash(), result.getDigestAlgorithm(), result.getTransferFile());
    }

    /**
     * A text stamp at the bottom of the last page. Further signatures are placed side by side, wrapping to a new row.
     */
    private static PadesVisualRepresentation visualRepresentation() {
        // {{name}} is filled from the signer's certificate, see
        // https://docs.lacunasoftware.com/articles/pki-express/pades-tags.html
        var text = new PadesVisualText("Assinado digitalmente por {{name}}", true, 9.0);
        // .NET format; PKI Express prefixes it with "Data:" and runs in PkiExpressOperators.TIME_ZONE.
        text.setSigningTimeFormat("dd/MM/yyyy HH:mm:ss '" + PkiExpressOperators.TIME_ZONE + "'");
        var textArea = new PadesVisualRectangle();
        textArea.setHorizontalStretch(0.2, 0.2);
        textArea.setVerticalStretch(0.2, 0.2);
        text.setContainer(textArea);

        // Measurements in centimeters (the default unit).
        var position = new PadesVisualAutoPositioning();
        position.setPageNumber(-1);
        position.setSignatureRectangleSize(new PadesSize(7.0, 2.0));
        position.setRowSpacing(0.0);
        var stampArea = new PadesVisualRectangle();
        stampArea.setHorizontalStretch(1.5, 1.5);
        stampArea.setHeightBottomAnchored(2.0, 1.5);
        position.setContainer(stampArea);

        var representation = new PadesVisualRepresentation();
        representation.setText(text);
        representation.setPosition(position);
        return representation;
    }
}
