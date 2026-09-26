package com.lacuna.document;

import com.lacuna.config.LacunaProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Keeps each document as an object in an S3 bucket, under {@code yyyy/MM/dd/<id>.<extension>} (the UTC date it was
 * stored), described by a row in the {@code document} table. The id is a UUID version 7, so ids and object names sort
 * by storage time. Objects carry their SHA-256, checked by the server on upload, and user metadata naming the
 * document. PKI Express works on local files: {@link #copyToWorkFolder} gives it a temporary copy.
 */
@Service
public class DocumentStorage {

    private static final String DEFAULT_NAME = "documento";
    private static final int MAX_NAME_LENGTH = 200;
    private static final DateTimeFormatter DAY_FOLDER = DateTimeFormatter.ofPattern("yyyy/MM/dd")
            .withZone(ZoneOffset.UTC);
    private static final HexFormat HEX = HexFormat.of();

    private final DocumentRepository repository;
    private final TransactionTemplate transactions;
    private final S3Client s3;
    private final String bucket;
    private final Path workDir;

    public DocumentStorage(DocumentRepository repository, PlatformTransactionManager transactionManager, S3Client s3,
                           LacunaProperties properties) throws IOException {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.s3 = s3;
        this.bucket = properties.storage().bucket();
        this.workDir = Files.createDirectories(properties.storage().dir().resolve("work"));
        createBucketIfMissing();
    }

    /**
     * Stores an uploaded file as a new document.
     *
     * @param name the file name given by the user; only used for display and downloads
     * @throws InvalidDocumentException if the content is empty
     */
    public StoredDocument store(String name, InputStream content) throws IOException {
        try (var file = newWorkFile()) {
            Files.copy(content, file.path());
            if (Files.size(file.path()) == 0) {
                throw new InvalidDocumentException("O arquivo enviado está vazio.");
            }
            return store(file.path(), name, null, document -> {
            });
        }
    }

    /**
     * Stores the file produced by signing {@code source} as a new document, leaving the file in place.
     *
     * @param recordSignature records the signature in the database, in the transaction that inserts the document's
     *                        row, so that neither is recorded without the other
     */
    public StoredDocument storeSigned(Path file, String name, StoredDocument source,
                                      Consumer<StoredDocument> recordSignature) throws IOException {
        return store(file, name, source.id(), recordSignature);
    }

    /**
     * @throws DocumentNotFoundException if the id is not a UUID in canonical form or no document has it
     */
    public StoredDocument find(String id) {
        return canonicalUuid(id).flatMap(repository::find).orElseThrow(() -> new DocumentNotFoundException(id));
    }

    /**
     * The content of a document, to be streamed; the caller closes it.
     */
    public InputStream openContent(StoredDocument document) {
        return s3.getObject(request -> request.bucket(document.bucket()).key(document.objectKey()));
    }

    /**
     * Copies a document to the working folder, for PKI Express.
     */
    public WorkFile copyToWorkFolder(StoredDocument document) throws IOException {
        var copy = newWorkFile();
        try {
            s3.getObject(request -> request.bucket(document.bucket()).key(document.objectKey()),
                    ResponseTransformer.toFile(copy.path()));
            return copy;
        } catch (RuntimeException e) {
            copy.close();
            throw e;
        }
    }

    /**
     * A path in the working folder for a file yet to be written, such as the output of a signature.
     */
    public WorkFile newWorkFile() {
        return new WorkFile(workDir.resolve(UUID.randomUUID().toString()));
    }

    private StoredDocument store(Path file, String name, @Nullable UUID sourceId, Consumer<StoredDocument> alsoRecord)
            throws IOException {
        // Milliseconds, as in the id; the database would round anything finer.
        var storedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var id = UuidV7.at(storedAt);
        var fileName = sanitize(name);
        var format = DocumentFormat.detect(file);
        var sha256 = sha256(file);
        var key = objectKey(id, storedAt, format.extension(fileName));
        var document = new StoredDocument(id, fileName, format, bucket, key, Files.size(file), HEX.formatHex(sha256),
                storedAt);

        s3.putObject(request -> request.bucket(bucket).key(key)
                        .contentType(format.mimeType())
                        .checksumSHA256(Base64.getEncoder().encodeToString(sha256))
                        .metadata(objectMetadata(document, sourceId)),
                RequestBody.fromFile(file));
        try {
            transactions.executeWithoutResult(transaction -> {
                repository.insert(document);
                alsoRecord.accept(document);
            });
        } catch (RuntimeException e) {
            // Nothing reaches an object without its row: remove it rather than leave it orphaned.
            try {
                s3.deleteObject(request -> request.bucket(bucket).key(key));
            } catch (RuntimeException deletion) {
                e.addSuppressed(deletion);
            }
            throw e;
        }
        return document;
    }

    /**
     * User metadata of the object, so the bucket makes sense without the database. S3 metadata is ASCII only, so the
     * file name is percent-encoded (UTF-8).
     */
    private static Map<String, String> objectMetadata(StoredDocument document, @Nullable UUID sourceId) {
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("document-id", document.id().toString());
        metadata.put("file-name", UriUtils.encode(document.name(), StandardCharsets.UTF_8));
        if (sourceId != null) {
            metadata.put("source-document-id", sourceId.toString());
        }
        return metadata;
    }

    private static byte[] sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform supports SHA-256", e);
        }
        try (var in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return digest.digest();
    }

    // yyyy/MM/dd in UTC, whatever the server's time zone: late in the evening west of Greenwich, already tomorrow.
    static String objectKey(UUID id, Instant storedAt, String extension) {
        return DAY_FOLDER.format(storedAt) + "/" + id + "." + extension;
    }

    private void createBucketIfMissing() {
        try {
            s3.headBucket(request -> request.bucket(bucket));
        } catch (NoSuchBucketException missing) {
            try {
                s3.createBucket(request -> request.bucket(bucket));
            } catch (BucketAlreadyOwnedByYouException createdMeanwhile) {
                // Another instance created it.
            }
        }
    }

    // Canonical form only, so each document has a single URL.
    private static Optional<UUID> canonicalUuid(String id) {
        try {
            var uuid = UUID.fromString(id);
            return uuid.toString().equals(id) ? Optional.of(uuid) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // Browsers may send a full client path; keep the last segment, and its end (the extension) if too long.
    static String sanitize(String name) {
        if (name == null) {
            return DEFAULT_NAME;
        }
        var fileName = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1)
                .replaceAll("\\p{Cntrl}", "")
                .strip();
        if (fileName.isEmpty()) {
            return DEFAULT_NAME;
        }
        return fileName.length() > MAX_NAME_LENGTH ? fileName.substring(fileName.length() - MAX_NAME_LENGTH) : fileName;
    }

    /**
     * A file in the working folder, deleted on {@link #close}.
     */
    public record WorkFile(Path path) implements AutoCloseable {

        @Override
        public void close() throws IOException {
            Files.deleteIfExists(path);
        }
    }
}
