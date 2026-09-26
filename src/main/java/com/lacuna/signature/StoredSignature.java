package com.lacuna.signature;

import com.lacuna.document.StoredDocument;
import com.lacuna.document.UuidV7;
import com.lacunasoftware.pkiexpress.PKCertificate;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * A signature made by the application, as recorded in the {@code signature} table.
 *
 * @param id                      UUID version 7
 * @param sourceDocumentId        the document that was signed
 * @param signedDocumentId        the document the signature produced
 * @param policy                  PKI Express signature policy
 * @param signedAt                as recorded in the signature itself
 * @param signerName              common name of the signer's certificate
 * @param signerCpf               ICP-Brasil CPF of the signer, digits only
 * @param signerCnpj              ICP-Brasil CNPJ of the signer's company, digits only
 * @param certificateIssuer       common name of the CA that issued the signer's certificate
 * @param certificateSerialNumber lowercase hex
 * @param certificateThumbprint   SHA-256 of the certificate, lowercase hex
 */
record StoredSignature(UUID id, UUID sourceDocumentId, UUID signedDocumentId, SignatureFormat format, String policy,
                       Instant signedAt, String signerName, @Nullable String signerEmail, @Nullable String signerCpf,
                       @Nullable String signerCnpj, String certificateIssuer, String certificateSerialNumber,
                       String certificateThumbprint, Instant certificateNotBefore, Instant certificateNotAfter) {

    static StoredSignature of(StoredDocument source, StoredDocument signed, SignatureFormat format, String policy,
                              Instant signedAt, PKCertificate certificate) {
        var pkiBrazil = certificate.getPkiBrazil();
        return new StoredSignature(
                UuidV7.at(Instant.now()),
                source.id(),
                signed.id(),
                format,
                policy,
                signedAt,
                certificate.getSubjectName().getCommonName(),
                blankToNull(certificate.getEmailAddress()),
                pkiBrazil != null ? digits(pkiBrazil.getCpf()) : null,
                pkiBrazil != null ? digits(pkiBrazil.getCnpj()) : null,
                certificate.getIssuerName().getCommonName(),
                certificate.getSerialNumber().toString(16),
                HexFormat.of().formatHex(certificate.getBinaryThumbprintSHA256()),
                certificate.getValidityStart().toInstant(),
                certificate.getValidityEnd().toInstant());
    }

    private static @Nullable String digits(@Nullable String value) {
        return value != null ? blankToNull(value.replaceAll("\\D", "")) : null;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
