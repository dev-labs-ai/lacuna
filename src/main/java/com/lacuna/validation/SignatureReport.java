package com.lacuna.validation;

import com.lacuna.signature.SignatureFormat;

import java.util.List;

/**
 * Signatures found in a file, with their validation.
 *
 * @param contentIncluded whether a CAdES signature carries the signed file; always true for PAdES
 */
public record SignatureReport(SignatureFormat format, List<SignerView> signers, boolean contentIncluded) {

    public long invalidCount() {
        return signers.stream().filter(signer -> !signer.valid()).count();
    }
}
