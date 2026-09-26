package com.lacuna.signature;

import com.lacuna.document.DocumentFormat;

import java.util.Arrays;
import java.util.List;

public enum SignatureFormat {

    /**
     * Signature embedded in the PDF itself.
     */
    PADES("PAdES"),
    /**
     * CMS signature file (.p7s) encapsulating the signed file; an existing .p7s is co-signed.
     */
    CADES("CAdES");

    private final String label;

    SignatureFormat(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * PDFs accept both formats; any other file, including an existing CAdES signature, is signed with CAdES.
     */
    public boolean supports(DocumentFormat documentFormat) {
        return this == CADES || documentFormat == DocumentFormat.PDF;
    }

    public static List<SignatureFormat> availableFor(DocumentFormat documentFormat) {
        return Arrays.stream(values()).filter(format -> format.supports(documentFormat)).toList();
    }
}
