package com.lacuna.document;

import com.lacuna.config.LacunaProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Keeps documents on disk, addressed by a random UUID, with their name and format in a JSON file alongside. Stands in
 * for whatever storage (database, object store) a real application would use.
 */
@Service
public class DocumentStorage {

    private static final String DEFAULT_NAME = "documento";
    private static final int MAX_NAME_LENGTH = 200;

    private final Path documentsDir;
    private final JsonMapper json;

    public DocumentStorage(LacunaProperties properties, JsonMapper json) throws IOException {
        this.documentsDir = Files.createDirectories(properties.storage().dir().resolve("documents"));
        this.json = json;
    }

    /**
     * Stores the content as a new document.
     *
     * @param name the file name given by the user; only used for display and downloads
     * @throws InvalidDocumentException if the content is empty
     */
    public StoredDocument store(String name, InputStream content) throws IOException {
        var reservation = reserve();
        Files.copy(content, reservation.path());
        if (Files.size(reservation.path()) == 0) {
            Files.delete(reservation.path());
            throw new InvalidDocumentException("O arquivo enviado está vazio.");
        }
        return commit(reservation, name);
    }

    /**
     * Reserves an id and path for a document to be written by someone else, such as the output of a signature.
     * The document only exists once {@linkplain #commit committed}.
     */
    public Reservation reserve() {
        var id = UUID.randomUUID().toString();
        return new Reservation(id, contentPath(id));
    }

    public StoredDocument commit(Reservation reservation, String name) throws IOException {
        var metadata = new Metadata(sanitize(name), DocumentFormat.detect(reservation.path()));
        json.writeValue(metadataPath(reservation.id()), metadata);
        return new StoredDocument(reservation.id(), metadata.name(), metadata.format(), reservation.path());
    }

    /**
     * @throws DocumentNotFoundException if the id is malformed or no document has it
     */
    public StoredDocument find(String id) {
        if (!isWellFormed(id) || !Files.isRegularFile(metadataPath(id))) {
            throw new DocumentNotFoundException(id);
        }
        var metadata = json.readValue(metadataPath(id), Metadata.class);
        return new StoredDocument(id, metadata.name(), metadata.format(), contentPath(id));
    }

    private Path contentPath(String id) {
        return documentsDir.resolve(id);
    }

    private Path metadataPath(String id) {
        return documentsDir.resolve(id + ".json");
    }

    // Only canonical UUIDs reach the file system, so ids like "../x" can never escape the documents folder.
    private static boolean isWellFormed(String id) {
        try {
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException e) {
            return false;
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

    public record Reservation(String id, Path path) {
    }

    record Metadata(String name, DocumentFormat format) {
    }
}
