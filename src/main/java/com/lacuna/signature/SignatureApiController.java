package com.lacuna.signature;

import com.lacuna.document.DocumentNotFoundException;
import com.lacuna.document.DocumentStorage;
import com.lacuna.document.InvalidDocumentException;
import com.lacuna.pkiexpress.PkiExpressException;
import com.lacunasoftware.pkiexpress.InstallationNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.util.UUID;

/**
 * JSON version of the signature round trips, for pages that sign several documents without reloading, such as the
 * batch signature. Errors are reported as RFC 9457 problem details, with the PKI Express report, when there is one,
 * in the {@code report} property.
 */
@RestController
@RequestMapping("/api")
public class SignatureApiController {

    private static final Logger log = LoggerFactory.getLogger(SignatureApiController.class);

    private final DocumentStorage storage;
    private final SignatureService signatures;

    public SignatureApiController(DocumentStorage storage, SignatureService signatures) {
        this.storage = storage;
        this.signatures = signatures;
    }

    /**
     * Called once before signing a batch, so that an unacceptable certificate is refused before the user authorizes
     * the signatures.
     */
    @PostMapping("/certificates/check")
    public ResponseEntity<Void> checkCertificate(@RequestBody CertificateRequest request) throws IOException {
        signatures.checkCertificate(request.certificate());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/documents/{id}/signature/start")
    public SignatureStart start(@PathVariable String id, @RequestBody StartRequest request) throws IOException {
        return signatures.start(storage.find(id), request.format(), request.certificate());
    }

    @PostMapping("/documents/{id}/signature/complete")
    public CompleteResponse complete(@PathVariable String id, @RequestBody CompleteRequest request) throws IOException {
        var signed = signatures.complete(storage.find(id), request.transferFileId(), request.signature(),
                request.certificate());
        var document = signed.document();
        var url = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/documents/{id}").buildAndExpand(document.id()).toUriString();
        return new CompleteResponse(document.id(), document.name(), url, signed.signerName());
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ProblemDetail documentNotFound() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "O documento não existe ou já foi removido.");
    }

    @ExceptionHandler(InvalidDocumentException.class)
    public ProblemDetail invalidDocument(InvalidDocumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(PkiExpressException.class)
    public ProblemDetail pkiExpressFailed(PkiExpressException e) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        if (e instanceof CertificateRejectedException) {
            problem.setTitle("Certificado não aceito");
        } else {
            log.warn("PKI Express operation failed", e);
        }
        if (e.details() != null) {
            problem.setProperty("report", e.details());
        }
        return problem;
    }

    @ExceptionHandler(InstallationNotFoundException.class)
    public ProblemDetail pkiExpressNotInstalled(InstallationNotFoundException e) {
        log.error("PKI Express installation not found", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "O PKI Express não está instalado neste servidor.");
    }

    // Thrown while reading the request body, so Spring answers 400 Bad Request.
    private static void required(Object... fields) {
        for (var field : fields) {
            if (field == null) {
                throw new IllegalArgumentException("Missing required field");
            }
        }
    }

    /**
     * @param certificate the signer's certificate (DER, Base64), as read by Web PKI
     */
    public record CertificateRequest(String certificate) {

        public CertificateRequest {
            required(certificate);
        }
    }

    public record StartRequest(SignatureFormat format, String certificate) {

        public StartRequest {
            required(format, certificate);
        }
    }

    /**
     * @param signature the hash signed by Web PKI (Base64)
     */
    public record CompleteRequest(String transferFileId, String signature, String certificate) {

        public CompleteRequest {
            required(transferFileId, signature, certificate);
        }
    }

    /**
     * @param url page of the signed document
     */
    public record CompleteResponse(UUID id, String name, String url, String signerName) {
    }
}
