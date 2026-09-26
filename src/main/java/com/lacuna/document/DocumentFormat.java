package com.lacuna.document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

/**
 * File formats the application tells apart, detected from the content rather than from the file name.
 */
public enum DocumentFormat {

    PDF("application/pdf", "pdf"),
    /**
     * CMS SignedData, i.e. a CAdES signature file (.p7s).
     */
    CMS("application/pkcs7-signature", "p7s"),
    /**
     * Anything else; its content is not inspected, so it is stored as plain bytes, with the extension of its name.
     */
    OTHER("application/octet-stream", null);

    private final String mimeType;
    private final String extension;

    DocumentFormat(String mimeType, String extension) {
        this.mimeType = mimeType;
        this.extension = extension;
    }

    public String mimeType() {
        return mimeType;
    }

    /**
     * Extension for a file of this format, without the dot: the format's own, or else the one in the file name
     * (letters and digits only), or else "bin".
     */
    public String extension(String fileName) {
        if (extension != null) {
            return extension;
        }
        var dot = fileName.lastIndexOf('.');
        var own = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return own.matches("[a-z0-9]{1,10}") ? own : "bin";
    }

    public static DocumentFormat ofMimeType(String mimeType) {
        return Arrays.stream(values()).filter(format -> format.mimeType.equals(mimeType)).findFirst().orElse(OTHER);
    }

    private static final byte[] PDF_HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    // DER of the content type OID of a CMS SignedData (1.2.840.113549.1.7.2).
    private static final byte[] SIGNED_DATA_OID = HexFormat.of().parseHex("06092a864886f70d010702");
    private static final int HEADER_LENGTH = 32;

    public static DocumentFormat detect(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            return detect(in.readNBytes(HEADER_LENGTH));
        }
    }

    public static DocumentFormat detect(InputStream content) throws IOException {
        return detect(content.readNBytes(HEADER_LENGTH));
    }

    private static DocumentFormat detect(byte[] header) {
        if (startsWith(header, 0, PDF_HEADER)) {
            return PDF;
        }
        if (isSignedData(header)) {
            return CMS;
        }
        return OTHER;
    }

    // A CMS file is a ContentInfo: SEQUENCE { contentType OBJECT IDENTIFIER, ... }.
    private static boolean isSignedData(byte[] header) {
        if (header.length < 2 || header[0] != 0x30) {
            return false;
        }
        int lengthByte = header[1] & 0xff;
        // Short form (< 0x80) or indefinite (0x80) take no extra bytes; long form is followed by n length bytes.
        int oidOffset = 2 + (lengthByte > 0x80 ? lengthByte & 0x7f : 0);
        return startsWith(header, oidOffset, SIGNED_DATA_OID);
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        return data.length >= offset + prefix.length
                && Arrays.equals(data, offset, offset + prefix.length, prefix, 0, prefix.length);
    }
}
