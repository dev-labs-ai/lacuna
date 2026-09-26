package com.lacuna.signature;

import com.lacuna.config.LacunaProperties;
import com.lacuna.document.DocumentFormat;
import com.lacuna.document.DocumentStorage;
import com.lacuna.document.InvalidDocumentException;
import com.lacuna.document.StoredDocument;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Signs several files at once. The files are uploaded together; the page then signs them one by one through
 * {@link SignatureApiController}, after the user authorizes all the signatures in Web PKI once.
 */
@Controller
@RequestMapping("/batch")
public class BatchSignatureController {

    // Tomcat accepts at most 50 parts per multipart request by default (server.tomcat.max-part-count).
    static final int MAX_FILES = 20;

    private final DocumentStorage storage;
    private final String webPkiLicense;

    public BatchSignatureController(DocumentStorage storage, LacunaProperties properties) {
        this.storage = storage;
        this.webPkiLicense = properties.webPki().license();
    }

    @ModelAttribute("maxFiles")
    public int maxFiles() {
        return MAX_FILES;
    }

    @GetMapping
    public String form() {
        return "batch";
    }

    @PostMapping
    public String upload(@RequestParam List<MultipartFile> files) throws IOException {
        var selected = files.stream().filter(file -> !file.isEmpty()).toList();
        requireBatchSize(selected.size());
        var ids = new ArrayList<String>();
        for (var file : selected) {
            try (var content = file.getInputStream()) {
                ids.add(storage.store(file.getOriginalFilename(), content).id().toString());
            }
        }
        return "redirect:" + UriComponentsBuilder.fromPath("/batch/sign").queryParam("documents", ids).toUriString();
    }

    @GetMapping("/sign")
    public String sign(@RequestParam List<String> documents, Model model) {
        requireBatchSize(documents.size());
        var batch = documents.stream().distinct().map(storage::find).toList();
        model.addAttribute("documents", batch);
        model.addAttribute("hasPdf", batch.stream().map(StoredDocument::format).anyMatch(DocumentFormat.PDF::equals));
        model.addAttribute("webPkiLicense", webPkiLicense);
        return "batch-sign";
    }

    private static void requireBatchSize(int size) {
        if (size == 0) {
            throw new InvalidDocumentException("Selecione ao menos um arquivo.");
        }
        if (size > MAX_FILES) {
            throw new InvalidDocumentException("Envie no máximo " + MAX_FILES + " arquivos por vez.");
        }
    }
}
