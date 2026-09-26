package com.lacuna.web;

import com.lacuna.document.DocumentNotFoundException;
import com.lacuna.document.InvalidDocumentException;
import com.lacuna.pkiexpress.PkiExpressException;
import com.lacunasoftware.pkiexpress.InstallationNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Renders the {@code error} view with a message the user can act on and a link back to where they were.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final Pattern SIGNATURE_PATH = Pattern.compile("^/documents/([^/]+)/sign(/.*)?$");

    @ExceptionHandler(DocumentNotFoundException.class)
    public ModelAndView documentNotFound() {
        return errorView(HttpStatus.NOT_FOUND, "Documento não encontrado",
                "O documento solicitado não existe ou já foi removido.", new Back("/", "Voltar ao início"));
    }

    @ExceptionHandler(InvalidDocumentException.class)
    public ModelAndView invalidDocument(InvalidDocumentException e, HttpServletRequest request) {
        return errorView(HttpStatus.BAD_REQUEST, "Arquivo inválido", e.getMessage(), backFrom(request));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ModelAndView uploadTooLarge(HttpServletRequest request) {
        return errorView(HttpStatus.CONTENT_TOO_LARGE, "Arquivo muito grande",
                "O arquivo excede o tamanho máximo permitido para envio.", backFrom(request));
    }

    @ExceptionHandler(PkiExpressException.class)
    public ModelAndView pkiExpressFailed(PkiExpressException e, HttpServletRequest request) {
        log.warn("PKI Express operation failed on {}", request.getRequestURI(), e);
        var title = SIGNATURE_PATH.matcher(path(request)).matches()
                ? "Não foi possível assinar o documento"
                : "Não foi possível concluir a operação";
        return errorView(HttpStatus.UNPROCESSABLE_CONTENT, title, e.getMessage(), backFrom(request));
    }

    @ExceptionHandler(InstallationNotFoundException.class)
    public ModelAndView pkiExpressNotInstalled(InstallationNotFoundException e) {
        log.error("PKI Express installation not found", e);
        return errorView(HttpStatus.SERVICE_UNAVAILABLE, "PKI Express não encontrado",
                "O PKI Express não está instalado neste servidor, ou a pasta configurada em lacuna.pki-express.home "
                        + "não contém o executável pkie. Veja https://docs.lacunasoftware.com/articles/pki-express/setup",
                new Back("/", "Voltar ao início"));
    }

    private static Back backFrom(HttpServletRequest request) {
        var path = path(request);
        var signature = SIGNATURE_PATH.matcher(path);
        if (signature.matches()) {
            return new Back("/documents/" + signature.group(1) + "/sign", "Tentar novamente");
        }
        if (path.startsWith("/validate")) {
            return new Back("/validate", "Validar outro arquivo");
        }
        return new Back("/", "Voltar ao início");
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private static ModelAndView errorView(HttpStatus status, String title, String message, Back back) {
        var view = new ModelAndView("error", Map.of(
                "status", status.value(),
                "title", title,
                "message", message,
                "backUrl", back.url(),
                "backLabel", back.label()));
        view.setStatus(status);
        return view;
    }

    private record Back(String url, String label) {
    }
}
