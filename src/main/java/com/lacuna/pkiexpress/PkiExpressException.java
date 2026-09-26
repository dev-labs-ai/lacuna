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

    private PkiExpressException(String message, Throwable cause) {
        super(message, cause);
        this.details = null;
    }

    public @Nullable String details() {
        return details;
    }

    /**
     * PKI Express reports failures as its console output, .NET stack trace included; keep only the explanation.
     */
    static PkiExpressException from(RuntimeException e) {
        if (e instanceof PkiExpressException pkiExpressException) {
            return pkiExpressException;
        }
        var message = e.getMessage() == null ? "" : e.getMessage().lines()
                .filter(line -> !line.stripLeading().startsWith("at "))
                .collect(Collectors.joining("\n"))
                .strip();
        return new PkiExpressException(message.isEmpty() ? "Falha inesperada no PKI Express." : message, e);
    }
}
