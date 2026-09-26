package com.lacuna.signature;

import com.lacuna.pkiexpress.PkiExpressOperators;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Checks a signer's certificate with PKI Express: chain up to a trusted root, validity period, revocation.
 */
@Service
public class CertificateValidator {

    private final PkiExpressOperators pkiExpress;

    public CertificateValidator(PkiExpressOperators pkiExpress) {
        this.pkiExpress = pkiExpress;
    }

    /**
     * @param certificateBase64 the certificate (DER, Base64), as read by Web PKI
     * @throws CertificateRejectedException if PKI Express does not accept the certificate
     */
    public void requireValid(String certificateBase64) throws IOException {
        var validation = pkiExpress.execute(pkiExpress.certificateExplorer(), explorer -> {
            explorer.setCertificateBase64(certificateBase64);
            explorer.setValidate(true);
            return explorer.open().getValidationResults();
        });
        if (validation != null && !validation.isValid()) {
            throw CertificateRejectedException.from(validation);
        }
    }
}
