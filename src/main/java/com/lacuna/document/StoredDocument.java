package com.lacuna.document;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * A document: an object in S3 and what the application knows about it.
 *
 * @param id        UUID version 7, which also names the object
 * @param name      original file name, only used for display and downloads
 * @param objectKey where the object is in the bucket: {@code yyyy/MM/dd/<id>.<extension>}, dated when stored (UTC)
 * @param signedAt  when the signature that produced this file was made; null for a file uploaded to be signed
 */
public record StoredDocument(UUID id, String name, DocumentFormat format, String bucket, String objectKey,
                             long sizeBytes, @Nullable Instant signedAt) {

    public String mimeType() {
        return format.mimeType();
    }

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
