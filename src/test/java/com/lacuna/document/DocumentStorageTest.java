package com.lacuna.document;

import com.lacuna.support.TestInfrastructure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Against PostgreSQL and MinIO in Docker.
 */
@SpringBootTest
@ImportTestcontainers(TestInfrastructure.class)
class DocumentStorageTest {

    private static final byte[] PDF = "%PDF-1.4\n...".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    static Path storageDir;

    @Autowired
    DocumentStorage storage;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    S3Client s3;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("lacuna.storage.dir", storageDir::toString);
    }

    @Test
    void storesAndFindsDocument() throws Exception {
        var stored = storage.store("contrato.pdf", new ByteArrayInputStream(PDF));

        var found = storage.find(stored.id().toString());
        assertThat(found).isEqualTo(stored);
        assertThat(found.id().version()).isEqualTo(7);
        assertThat(found.name()).isEqualTo("contrato.pdf");
        assertThat(found.format()).isEqualTo(DocumentFormat.PDF);
        assertThat(found.sizeBytes()).isEqualTo(PDF.length);
        assertThat(found.signedAt()).isNull();
        try (var content = storage.openContent(found)) {
            assertThat(content.readAllBytes()).isEqualTo(PDF);
        }
    }

    @Test
    void namesObjectsByUtcDateAndId() throws Exception {
        var today = DateTimeFormatter.ofPattern("yyyy/MM/dd").format(OffsetDateTime.now(ZoneOffset.UTC));

        var stored = storage.store("contrato.pdf", new ByteArrayInputStream(PDF));

        assertThat(stored.bucket()).isEqualTo("documents");
        assertThat(stored.objectKey()).isEqualTo(today + "/" + stored.id() + ".pdf");
        var object = s3.headObject(request -> request.bucket("documents").key(stored.objectKey()));
        assertThat(object.contentLength()).isEqualTo(PDF.length);
        assertThat(object.contentType()).isEqualTo("application/pdf");
    }

    @Test
    void keepsTheExtensionOfOtherFiles() throws Exception {
        var stored = storage.store("planilha.xlsx", new ByteArrayInputStream(new byte[] {'P', 'K', 3, 4}));

        assertThat(stored.format()).isEqualTo(DocumentFormat.OTHER);
        assertThat(stored.mimeType()).isEqualTo("application/octet-stream");
        assertThat(stored.objectKey()).endsWith("/" + stored.id() + ".xlsx");
    }

    @Test
    void recordsWhatIsKnownAboutTheFile() throws Exception {
        var signedAt = Instant.parse("2026-09-26T16:40:45Z");
        var file = Files.write(storageDir.resolve("assinado.pdf"), PDF);

        var stored = storage.storeSigned(file, "contrato.pdf", signedAt);

        var row = jdbc.sql("SELECT * FROM document WHERE id = :id").param("id", stored.id()).query().singleRow();
        assertThat(row).containsEntry("bucket", "documents")
                .containsEntry("object_key", stored.objectKey())
                .containsEntry("file_name", "contrato.pdf")
                .containsEntry("size_bytes", (long) PDF.length)
                .containsEntry("mime_type", "application/pdf");
        assertThat(jdbc.sql("SELECT signed_at AT TIME ZONE 'UTC' FROM document WHERE id = :id")
                .param("id", stored.id()).query(String.class).single()).isEqualTo("2026-09-26 16:40:45");
        assertThat(storage.find(stored.id().toString()).signedAt()).isEqualTo(signedAt);
        assertThat(file).exists();
    }

    @Test
    void rejectsEmptyFile() {
        var rows = countDocuments();

        assertThatThrownBy(() -> storage.store("vazio.pdf", new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(InvalidDocumentException.class);
        assertThat(countDocuments()).isEqualTo(rows);
        assertThat(storageDir.resolve("work")).isEmptyDirectory();
    }

    @Test
    void copiesDocumentsToTheWorkFolderUntilClosed() throws Exception {
        var stored = storage.store("contrato.pdf", new ByteArrayInputStream(PDF));

        Path copy;
        try (var file = storage.copyToWorkFolder(stored)) {
            copy = file.path();
            assertThat(copy).hasBinaryContent(PDF).startsWith(storageDir.resolve("work"));
        }
        assertThat(copy).doesNotExist();
    }

    /**
     * Half an hour after and before midnight UTC: on a machine west of UTC the local date of the first is still the
     * 26th, east of it the local date of the second is already the 27th, so a local date fails at least one.
     */
    @ParameterizedTest
    @CsvSource({
            // 20:30 on the 26th in Cuiabá (UTC-4) is 00:30 on the 27th in UTC
            "2026-09-26T20:30:00-04:00, 2026/09/27",
            // 02:30 on the 27th in Moscow (UTC+3) is 23:30 on the 26th in UTC
            "2026-09-27T02:30:00+03:00, 2026/09/26",
    })
    void datesObjectKeysInUtc(OffsetDateTime storedAt, String folder) {
        var id = UUID.fromString("01a0dec1-1590-7bd0-ab56-8d349e958db8");

        assertThat(DocumentStorage.objectKey(id, storedAt.toInstant(), "pdf")).isEqualTo(folder + "/" + id + ".pdf");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../documents/x", "not-a-uuid", "4B3C1E0A-9A7B-4C1D-8E2F-0A1B2C3D4E5F", "1-1-1-1-1"})
    void rejectsMalformedIds(String id) {
        assertThatThrownBy(() -> storage.find(id)).isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void rejectsUnknownId() {
        assertThatThrownBy(() -> storage.find(UUID.randomUUID().toString()))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "contrato.pdf|contrato.pdf",
            "C:\\Users\\ana\\contrato.pdf|contrato.pdf",
            "/home/ana/relatório final.docx|relatório final.docx",
            "'  '|documento",
            "pasta/|documento",
    })
    void keepsOnlyTheFileNameOfUploads(String uploaded, String expected) {
        assertThat(DocumentStorage.sanitize(uploaded)).isEqualTo(expected);
    }

    @Test
    void shortensLongNamesKeepingTheExtension() {
        var name = "a".repeat(300) + ".pdf";

        assertThat(DocumentStorage.sanitize(name)).hasSize(200).endsWith(".pdf");
    }

    private long countDocuments() {
        return jdbc.sql("SELECT count(*) FROM document").query(Long.class).single();
    }
}
