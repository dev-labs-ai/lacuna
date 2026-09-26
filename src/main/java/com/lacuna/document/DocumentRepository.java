package com.lacuna.document;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
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
        jdbc.sql("""
                        INSERT INTO document (id, bucket, object_key, file_name, size_bytes, mime_type, sha256, stored_at)
                        VALUES (:id, :bucket, :objectKey, :fileName, :sizeBytes, :mimeType, :sha256, :storedAt)
                        """)
                .param("id", document.id())
                .param("bucket", document.bucket())
                .param("objectKey", document.objectKey())
                .param("fileName", document.name())
                .param("sizeBytes", document.sizeBytes())
                .param("mimeType", document.mimeType())
                .param("sha256", document.sha256())
                .param("storedAt", document.storedAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    Optional<StoredDocument> find(UUID id) {
        return jdbc.sql("""
                        SELECT id, bucket, object_key, file_name, size_bytes, mime_type, sha256, stored_at
                        FROM document
                        WHERE id = :id
                        """)
                .param("id", id)
                .query(DocumentRepository::toDocument)
                .optional();
    }

    private static StoredDocument toDocument(ResultSet row, int rowNumber) throws SQLException {
        return new StoredDocument(
                row.getObject("id", UUID.class),
                row.getString("file_name"),
                DocumentFormat.ofMimeType(row.getString("mime_type")),
                row.getString("bucket"),
                row.getString("object_key"),
                row.getLong("size_bytes"),
                row.getString("sha256"),
                row.getObject("stored_at", OffsetDateTime.class).toInstant());
    }
}
