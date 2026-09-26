package com.lacuna.document;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code document} table (db/migration). Times are written in UTC.
 */
@Repository
class DocumentRepository {

    private final JdbcClient jdbc;

    DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(StoredDocument document) {
        var signedAt = document.signedAt() != null ? document.signedAt().atOffset(ZoneOffset.UTC) : null;
        jdbc.sql("""
                        INSERT INTO document (id, bucket, object_key, file_name, size_bytes, mime_type, signed_at)
                        VALUES (:id, :bucket, :objectKey, :fileName, :sizeBytes, :mimeType, :signedAt)
                        """)
                .param("id", document.id())
                .param("bucket", document.bucket())
                .param("objectKey", document.objectKey())
                .param("fileName", document.name())
                .param("sizeBytes", document.sizeBytes())
                .param("mimeType", document.mimeType())
                .param("signedAt", signedAt, Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    Optional<StoredDocument> find(UUID id) {
        return jdbc.sql("""
                        SELECT id, bucket, object_key, file_name, size_bytes, mime_type, signed_at
                        FROM document
                        WHERE id = :id
                        """)
                .param("id", id)
                .query(DocumentRepository::toDocument)
                .optional();
    }

    private static StoredDocument toDocument(ResultSet row, int rowNumber) throws SQLException {
        var signedAt = row.getObject("signed_at", OffsetDateTime.class);
        return new StoredDocument(
                row.getObject("id", UUID.class),
                row.getString("file_name"),
                DocumentFormat.ofMimeType(row.getString("mime_type")),
                row.getString("bucket"),
                row.getString("object_key"),
                row.getLong("size_bytes"),
                signedAt != null ? signedAt.toInstant() : null);
    }
}
