package com.lacuna.pkiexpress;

import org.jspecify.annotations.Nullable;

import java.util.stream.Collectors;

/**
 * A signature or validation was refused, usually by PKI Express (untrusted certificate, invalid signature value,
 * etc.). The message is meant to be shown to the user.
 */
public class PkiExpressException extends RuntimeException {

    private final @Nullable String details;

    public PkiExpressException(String message) {
        this(message, (String) null);
    }

    /**
     * @param details technical report, such as PKI Express' validation results, shown on request
     */
    public PkiExpressException(String message, @Nullable String details) {
        super(message);
        this.details = details;
    }

    private PkiExpressException(String message, @Nullable String details, Throwable cause) {
        super(message, cause);
        this.details = details;
    }

    public @Nullable String details() {
        return details;
    }

    /**
     * PKI Express reports failures as its console output, .NET exceptions and stack traces included.
     */
    static PkiExpressException from(RuntimeException e) {
        if (e instanceof PkiExpressException pkiExpressException) {
            return pkiExpressException;
        }
        var output = withoutStackTraces(e.getMessage());
        if (output.isEmpty()) {
            return new PkiExpressException("Falha inesperada no PKI Express.", null, e);
        }
        // PKI Express fails with this .NET exception on a file it cannot parse as a PDF.
        if (output.contains("InvalidPdfException")) {
            return new PkiExpressException("O arquivo não é um PDF válido ou está corrompido.", output, e);
        }
        return new PkiExpressException(output, null, e);
    }

    private static String withoutStackTraces(@Nullable String output) {
        if (output == null) {
            return "";
        }
        return output.lines()
                .filter(line -> {
                    var content = line.strip();
                    return !content.startsWith("at ")
                            && !content.startsWith("--->")
                            && !content.startsWith("--- End of inner exception");
                })
                .collect(Collectors.joining("\n"))
                .strip();
    }
}
