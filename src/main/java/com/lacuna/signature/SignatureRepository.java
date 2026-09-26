package com.lacuna.signature;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.ZoneOffset;

/**
 * The {@code signature} table (db/migration). Times are written in UTC.
 */
@Repository
class SignatureRepository {

    private final JdbcClient jdbc;

    SignatureRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(StoredSignature signature) {
        jdbc.sql("""
                        INSERT INTO signature (id, source_document_id, signed_document_id, format, policy, signed_at,
                                               signer_name, signer_email, signer_cpf, signer_cnpj,
                                               certificate_issuer, certificate_serial_number, certificate_thumbprint,
                                               certificate_not_before, certificate_not_after)
                        VALUES (:id, :sourceDocumentId, :signedDocumentId, :format, :policy, :signedAt,
                                :signerName, :signerEmail, :signerCpf, :signerCnpj,
                                :certificateIssuer, :certificateSerialNumber, :certificateThumbprint,
                                :certificateNotBefore, :certificateNotAfter)
                        """)
                .param("id", signature.id())
                .param("sourceDocumentId", signature.sourceDocumentId())
                .param("signedDocumentId", signature.signedDocumentId())
                .param("format", signature.format().name())
                .param("policy", signature.policy())
                .param("signedAt", signature.signedAt().atOffset(ZoneOffset.UTC))
                .param("signerName", signature.signerName())
                .param("signerEmail", signature.signerEmail())
                .param("signerCpf", signature.signerCpf())
                .param("signerCnpj", signature.signerCnpj())
                .param("certificateIssuer", signature.certificateIssuer())
                .param("certificateSerialNumber", signature.certificateSerialNumber())
                .param("certificateThumbprint", signature.certificateThumbprint())
                .param("certificateNotBefore", signature.certificateNotBefore().atOffset(ZoneOffset.UTC))
                .param("certificateNotAfter", signature.certificateNotAfter().atOffset(ZoneOffset.UTC))
                .update();
    }
}
