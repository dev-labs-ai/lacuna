package com.lacuna.document;

import com.lacuna.pkiexpress.PkiExpressException;
import com.lacuna.validation.ValidationService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@Controller
public class DocumentController {

    private static final String SAMPLE_NAME = "documento-exemplo.pdf";
    private static final ClassPathResource SAMPLE_PDF = new ClassPathResource("samples/" + SAMPLE_NAME);

    private final DocumentStorage storage;
    private final ValidationService validation;

    public DocumentController(DocumentStorage storage, ValidationService validation) {
        this.storage = storage;
        this.validation = validation;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @PostMapping("/documents")
    public String upload(@RequestParam MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new InvalidDocumentException("Selecione um arquivo.");
        }
        try (var content = file.getInputStream()) {
            return "redirect:/documents/" + storage.store(file.getOriginalFilename(), content).id() + "/sign";
        }
    }

    @PostMapping("/documents/sample")
    public String useSample() throws IOException {
        try (var content = SAMPLE_PDF.getInputStream()) {
            return "redirect:/documents/" + storage.store(SAMPLE_NAME, content).id() + "/sign";
        }
    }

    @GetMapping("/documents/{id}")
    public String show(@PathVariable String id, Model model) throws IOException {
        var document = storage.find(id);
        model.addAttribute("document", document);
        if (document.format() != DocumentFormat.OTHER) {
            try {
                model.addAttribute("report", validation.validate(document.path(), null));
            } catch (PkiExpressException e) {
                model.addAttribute("reportError", e.getMessage());
            }
        }
        return "document";
    }

    @GetMapping("/documents/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable String id) {
        var document = storage.find(id);
        return download(new FileSystemResource(document.path()), document.name(), document.format() == DocumentFormat.PDF);
    }

    /**
     * The file encapsulated in a CAdES signature.
     */
    @GetMapping("/documents/{id}/content")
    public ResponseEntity<Resource> content(@PathVariable String id) throws IOException {
        var document = storage.find(id);
        if (document.format() != DocumentFormat.CMS) {
            throw new DocumentNotFoundException(id);
        }
        var extracted = Files.createTempFile("lacuna-content", null);
        try {
            validation.extractContent(document.path(), extracted);
            return download(new ByteArrayResource(Files.readAllBytes(extracted)), document.contentName(), false);
        } finally {
            Files.deleteIfExists(extracted);
        }
    }

    /**
     * Only PDFs are shown in the browser; anything else is downloaded, so an uploaded HTML page, for instance, never
     * runs on this site.
     */
    private static ResponseEntity<Resource> download(Resource body, String name, boolean inlinePdf) {
        var disposition = inlinePdf ? ContentDisposition.inline() : ContentDisposition.attachment();
        return ResponseEntity.ok()
                .contentType(inlinePdf ? MediaType.APPLICATION_PDF : MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.filename(name, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }
}
