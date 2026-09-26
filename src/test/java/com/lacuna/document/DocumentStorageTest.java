package com.lacuna.document;

import com.lacuna.config.LacunaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentStorageTest {

    @TempDir
    Path dir;

    DocumentStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        storage = new DocumentStorage(new LacunaProperties(
                new LacunaProperties.PkiExpress(null, false, List.of(), false, null, null, null, null),
                new LacunaProperties.WebPki(null),
                new LacunaProperties.Storage(dir)), new JsonMapper());
    }

    @Test
    void storesAndFindsDocument() throws Exception {
        var content = "%PDF-1.4\n...".getBytes(StandardCharsets.US_ASCII);

        var stored = storage.store("contrato.pdf", new ByteArrayInputStream(content));

        var found = storage.find(stored.id());
        assertThat(found).isEqualTo(stored);
        assertThat(found.name()).isEqualTo("contrato.pdf");
        assertThat(found.format()).isEqualTo(DocumentFormat.PDF);
        assertThat(Files.readAllBytes(found.path())).isEqualTo(content);
    }

    @Test
    void storesAnyKindOfFile() throws Exception {
        var stored = storage.store("planilha.xlsx", new ByteArrayInputStream(new byte[] {'P', 'K', 3, 4}));

        assertThat(storage.find(stored.id()).format()).isEqualTo(DocumentFormat.OTHER);
    }

    @Test
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> storage.store("vazio.pdf", new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(InvalidDocumentException.class);
        assertThat(dir.resolve("documents")).isEmptyDirectory();
    }

    @Test
    void reservedDocumentExistsOnlyOnceCommitted() throws Exception {
        var reservation = storage.reserve();
        Files.writeString(reservation.path(), "conteúdo");

        assertThatThrownBy(() -> storage.find(reservation.id())).isInstanceOf(DocumentNotFoundException.class);
        assertThat(storage.commit(reservation, "nota.txt").name()).isEqualTo("nota.txt");
        assertThat(storage.find(reservation.id()).name()).isEqualTo("nota.txt");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../documents/x", "not-a-uuid", "4B3C1E0A-9A7B-4C1D-8E2F-0A1B2C3D4E5F", "1-1-1-1-1"})
    void rejectsMalformedIds(String id) {
        assertThatThrownBy(() -> storage.find(id)).isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void rejectsUnknownId() {
        var id = storage.reserve().id();

        assertThatThrownBy(() -> storage.find(id)).isInstanceOf(DocumentNotFoundException.class);
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
}
