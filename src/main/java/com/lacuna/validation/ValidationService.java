package com.lacuna.validation;

import com.lacuna.document.DocumentFormat;
import com.lacuna.document.InvalidDocumentException;
import com.lacuna.pkiexpress.PkiExpressException;
import com.lacuna.pkiexpress.PkiExpressOperators;
import com.lacuna.signature.SignatureFormat;
import com.lacunasoftware.pkiexpress.CadesSignerInfo;
import com.lacunasoftware.pkiexpress.PadesSignerInfo;
import com.lacunasoftware.pkiexpress.ValidationItem;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Opens and validates PAdES (PDF) and CAdES (.p7s) signatures with PKI Express.
 */
@Service
public class ValidationService {

    private final PkiExpressOperators pkiExpress;

    public ValidationService(PkiExpressOperators pkiExpress) {
        this.pkiExpress = pkiExpress;
    }

    /**
     * @param signedFile a PDF or a CAdES signature file
     * @param dataFile   the file that was signed, needed only for a detached CAdES signature (one that does not
     *                   carry the signed file)
     * @throws InvalidDocumentException if the file is neither a PDF nor a CAdES signature
     */
    public SignatureReport validate(Path signedFile, @Nullable Path dataFile) throws IOException {
        return switch (DocumentFormat.detect(signedFile)) {
            case PDF -> validatePades(signedFile);
            case CMS -> validateCades(signedFile, dataFile);
            case OTHER -> throw new InvalidDocumentException(
                    "O arquivo não é um PDF nem uma assinatura CAdES (.p7s), então não há assinaturas para validar.");
        };
    }

    /**
     * Writes the file encapsulated in a CAdES signature to {@code output}.
     */
    public void extractContent(Path cadesSignature, Path output) throws IOException {
        pkiExpress.execute(pkiExpress.cadesSignatureExplorer(), explorer -> {
            explorer.setSignatureFile(cadesSignature);
            explorer.setExtractContentPath(output);
            return explorer.open();
        });
    }

    private SignatureReport validatePades(Path pdf) throws IOException {
        var signers = pkiExpress.execute(pkiExpress.padesSignatureExplorer(), explorer -> {
            explorer.setSignatureFile(pdf);
            explorer.setValidate(true);
            return explorer.open().getSigners();
        });
        return new SignatureReport(SignatureFormat.PADES, signers.stream().map(this::toView).toList(), true);
    }

    private SignatureReport validateCades(Path signature, @Nullable Path dataFile) throws IOException {
        try {
            var cades = pkiExpress.execute(pkiExpress.cadesSignatureExplorer(), explorer -> {
                explorer.setSignatureFile(signature);
                if (dataFile != null) {
                    explorer.setDataFile(dataFile);
                }
                explorer.setValidate(true);
                return explorer.open();
            });
            var signers = cades.getSigners().stream().map(this::toView).toList();
            return new SignatureReport(SignatureFormat.CADES, signers, cades.getHasEncapsulatedContent());
        } catch (PkiExpressException e) {
            // PKI Express refuses to validate a detached signature without the signed file; say so in plain words.
            if (dataFile == null && !hasEncapsulatedContent(signature)) {
                throw new PkiExpressException("Esta assinatura CAdES é destacada: o arquivo assinado não está dentro "
                        + "do .p7s. Envie também o arquivo original para validá-la.");
            }
            throw e;
        }
    }

    private boolean hasEncapsulatedContent(Path signature) throws IOException {
        return pkiExpress.execute(pkiExpress.cadesSignatureExplorer(), explorer -> {
            explorer.setSignatureFile(signature);
            return explorer.open().getHasEncapsulatedContent();
        });
    }

    private SignerView toView(CadesSignerInfo signer) {
        var certificate = signer.getCertificate();
        var pkiBrazil = certificate.getPkiBrazil();
        var validation = signer.getValidationResults();
        return new SignerView(
                certificate.getSubjectName().getCommonName(),
                certificate.getEmailAddress(),
                pkiBrazil != null ? pkiBrazil.getCpfFormatted() : "",
                certificate.getIssuerName().getCommonName(),
                signer.getSigningTime() != null ? signer.getSigningTime().toInstant().atZone(pkiExpress.zone()) : null,
                signer instanceof PadesSignerInfo pades && pades.getIsDocumentTimestamp(),
                validation != null && validation.isValid(),
                validation != null ? messages(validation.getErrors()) : List.of(),
                validation != null ? messages(validation.getWarnings()) : List.of(),
                validation != null ? validation.toString() : "");
    }

    private static List<String> messages(List<ValidationItem> items) {
        return items.stream()
                .map(item -> StringUtils.hasText(item.getDetail())
                        ? item.getMessage() + " (" + item.getDetail() + ")"
                        : item.getMessage())
                .toList();
    }
}
