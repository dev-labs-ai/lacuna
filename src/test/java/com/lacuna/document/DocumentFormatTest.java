package com.lacuna.document;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentFormatTest {

    @ParameterizedTest
    @CsvSource({
            // "%PDF-1.7"
            "255044462d312e37, PDF",
            // CMS SignedData with long-form (2 bytes) length, as written by PKI Express
            "30827901 06092a864886f70d010702 a0827800, CMS",
            // short-form length
            "3050 06092a864886f70d010702 a043, CMS",
            // indefinite length (BER)
            "3080 06092a864886f70d010702 a080, CMS",
            // CMS EnvelopedData (1.2.840.113549.1.7.3) is not a signature
            "30827901 06092a864886f70d010703 a0827800, OTHER",
            // ZIP (docx, xlsx...)
            "504b0304, OTHER",
            "'', OTHER",
    })
    void detectsFormatFromContent(String hex, DocumentFormat expected) throws Exception {
        var content = HexFormat.of().parseHex(hex.replace(" ", ""));

        assertThat(DocumentFormat.detect(new ByteArrayInputStream(content))).isEqualTo(expected);
    }
}
