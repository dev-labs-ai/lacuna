package com.lacuna.signature;

import com.lacuna.document.StoredDocument;

/**
 * @param document   the signed file, stored as a new document
 * @param signerName common name of the signer's certificate
 */
public record SignedDocument(StoredDocument document, String signerName) {
}
