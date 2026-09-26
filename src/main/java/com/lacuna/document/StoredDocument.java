package com.lacuna.document;

import java.nio.file.Path;
import java.util.Locale;

/**
 * @param name original file name, only used for display and downloads
 * @param path where the content is kept
 */
public record StoredDocument(String id, String name, DocumentFormat format, Path path) {

    /**
     * Name of this document once signed: PAdES keeps the PDF name, CAdES wraps the file in a .p7s.
     */
    public String signedName(DocumentFormat signedFormat) {
        return signedFormat == DocumentFormat.CMS && format != DocumentFormat.CMS ? name + ".p7s" : name;
    }

    /**
     * Name of the file encapsulated in this CAdES signature, e.g. "contrato.pdf" for "contrato.pdf.p7s".
     */
    public String contentName() {
        var lowerCase = name.toLowerCase(Locale.ROOT);
        if ((lowerCase.endsWith(".p7s") || lowerCase.endsWith(".p7m")) && name.length() > 4) {
            return name.substring(0, name.length() - 4);
        }
        return "conteudo-" + name;
    }
}
