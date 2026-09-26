package com.lacuna.signature;

import com.lacuna.config.LacunaProperties;
import com.lacuna.document.DocumentStorage;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;

/**
 * The three round trips of a signature with PKI Express and Web PKI
 * (https://docs.lacunasoftware.com/articles/pki-express/java/how-it-works):
 * <ol>
 *     <li>{@code GET}: the page lists the user's certificates with Web PKI and posts the chosen one, with the
 *     signature format, to {@code start};</li>
 *     <li>{@code POST start}: PKI Express computes the hash to sign; the page signs it with Web PKI and posts the
 *     signature to {@code complete};</li>
 *     <li>{@code POST complete}: PKI Express writes the signed file, stored as a new document.</li>
 * </ol>
 */
@Controller
@RequestMapping("/documents/{id}/sign")
public class SignatureController {

    private final DocumentStorage storage;
    private final SignatureService signatures;
    private final String webPkiLicense;

    public SignatureController(DocumentStorage storage, SignatureService signatures, LacunaProperties properties) {
        this.storage = storage;
        this.signatures = signatures;
        this.webPkiLicense = properties.webPki().license();
    }

    @ModelAttribute("webPkiLicense")
    public String webPkiLicense() {
        return webPkiLicense;
    }

    @GetMapping
    public String selectCertificate(@PathVariable String id, Model model) {
        var document = storage.find(id);
        model.addAttribute("document", document);
        model.addAttribute("formats", SignatureFormat.availableFor(document.format()));
        return "sign";
    }

    @PostMapping("/start")
    public String start(
            @PathVariable String id,
            @RequestParam SignatureFormat format,
            @RequestParam String certThumb,
            @RequestParam String certContent,
            Model model) throws IOException {
        var document = storage.find(id);
        signatures.checkCertificate(certContent);
        model.addAttribute("document", document);
        model.addAttribute("format", format);
        model.addAttribute("certThumb", certThumb);
        model.addAttribute("certContent", certContent);
        model.addAttribute("start", signatures.start(document, format, certContent));
        return "sign-complete";
    }

    @PostMapping("/complete")
    public String complete(
            @PathVariable String id,
            @RequestParam String transferFileId,
            @RequestParam String signature,
            @RequestParam String certContent,
            RedirectAttributes redirect) throws IOException {
        var signed = signatures.complete(storage.find(id), transferFileId, signature, certContent);
        redirect.addFlashAttribute("signedBy", signed.signerName());
        return "redirect:/documents/" + signed.document().id();
    }
}
