package com.lacuna.validation;

import com.lacuna.document.InvalidDocumentException;
import com.lacuna.pkiexpress.PkiExpressException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

/**
 * Validates signed files uploaded by the user, without storing them.
 */
@Controller
@RequestMapping("/validate")
public class ValidationController {

    private final ValidationService validation;

    public ValidationController(ValidationService validation) {
        this.validation = validation;
    }

    @GetMapping
    public String form() {
        return "validate";
    }

    /**
     * @param originalFile the signed file, needed only for detached CAdES signatures
     */
    @PostMapping
    public String validate(
            @RequestParam MultipartFile signedFile,
            @RequestParam(required = false) MultipartFile originalFile,
            Model model) throws IOException {
        if (signedFile.isEmpty()) {
            throw new InvalidDocumentException("Selecione o arquivo assinado.");
        }
        var workDir = Files.createTempDirectory("lacuna-validation");
        try {
            var signed = workDir.resolve("signed");
            signedFile.transferTo(signed);
            var original = originalFile == null || originalFile.isEmpty() ? null : workDir.resolve("original");
            if (original != null) {
                originalFile.transferTo(original);
            }
            model.addAttribute("fileName", signedFile.getOriginalFilename());
            model.addAttribute("report", validation.validate(signed, original));
            return "validate";
        } finally {
            FileSystemUtils.deleteRecursively(workDir);
        }
    }

    // Keep the user on the validator, with the reason next to the form.
    @ExceptionHandler({InvalidDocumentException.class, PkiExpressException.class})
    public ModelAndView validationFailed(RuntimeException e) {
        var view = new ModelAndView("validate", Map.of("error", e.getMessage()));
        view.setStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        return view;
    }
}
