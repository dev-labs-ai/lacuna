package com.lacuna.validation;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One signature found in a PDF, as shown on the document page.
 *
 * @param cpf         formatted CPF for ICP-Brasil certificates, otherwise empty
 * @param signingTime in UTC; the page shows it in the reader's time zone
 * @param timestamp   whether this is a document timestamp rather than a person's signature
 * @param report      full validation report produced by PKI Express
 */
public record SignerView(
        String name,
        String email,
        String cpf,
        String issuer,
        OffsetDateTime signingTime,
        boolean timestamp,
        boolean valid,
        List<String> errors,
        List<String> warnings,
        String report) {
}
