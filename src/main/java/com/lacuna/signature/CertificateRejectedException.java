package com.lacuna.signature;

import com.lacuna.pkiexpress.PkiExpressException;
import com.lacunasoftware.pkiexpress.ValidationItem;
import com.lacunasoftware.pkiexpress.ValidationItemTypes;
import com.lacunasoftware.pkiexpress.ValidationResults;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PKI Express does not accept the signer's certificate. The message explains why in plain words; the details carry
 * the full validation report.
 */
public class CertificateRejectedException extends PkiExpressException {

    // Checked in this order, so the root cause wins over its consequences (an untrusted root also makes every
    // issuer in the chain invalid).
    private static final Map<ValidationItemTypes, String> REASONS = new LinkedHashMap<>();

    static {
        REASONS.put(ValidationItemTypes.CertificateChainRootNotTrusted,
                "O certificado não foi emitido por uma autoridade certificadora confiável.");
        REASONS.put(ValidationItemTypes.UnknownRootTrustStatus,
                "O certificado não foi emitido por uma autoridade certificadora confiável.");
        REASONS.put(ValidationItemTypes.CertificateRevoked, "O certificado foi revogado.");
        REASONS.put(ValidationItemTypes.CertificateExpired, "O certificado está vencido.");
        REASONS.put(ValidationItemTypes.CertificateNotYetValid, "O certificado ainda não está válido.");
        REASONS.put(ValidationItemTypes.InvalidKeyUsage, "O certificado não permite assinaturas digitais.");
        REASONS.put(ValidationItemTypes.CertificateIssuerNotFound,
                "Não foi possível encontrar a autoridade certificadora que emitiu o certificado.");
        REASONS.put(ValidationItemTypes.CertificateRevocationStatusUnknown,
                "Não foi possível verificar se o certificado foi revogado. Tente novamente mais tarde.");
    }

    private CertificateRejectedException(String message, String details) {
        super(message, details);
    }

    static CertificateRejectedException from(ValidationResults validation) {
        var errors = errorsOf(validation);
        return new CertificateRejectedException(reason(errors), validation.toString());
    }

    private static String reason(List<ValidationItem> errors) {
        for (var reason : REASONS.entrySet()) {
            for (var error : errors) {
                if (error.getType() == reason.getKey()) {
                    // The detail names the certificate involved, e.g. "A raiz Lacuna Root Test v3 não é confiável".
                    return StringUtils.hasText(error.getDetail())
                            ? reason.getValue() + " (" + error.getDetail() + ")"
                            : reason.getValue();
                }
            }
        }
        return "O certificado não passou na validação.";
    }

    // Errors about an issuer are nested in the error about the certificate it issued.
    private static List<ValidationItem> errorsOf(ValidationResults validation) {
        var errors = new ArrayList<ValidationItem>();
        for (var error : validation.getErrors()) {
            errors.add(error);
            if (error.getInnerValidationResults() != null) {
                errors.addAll(errorsOf(error.getInnerValidationResults()));
            }
        }
        return errors;
    }
}
