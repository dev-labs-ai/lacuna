package com.lacuna.signature;

/**
 * Outcome of the first step of a remote signature: the hash the user must sign with Web PKI, and the id of the transfer
 * file PKI Express needs to finish the signature.
 */
public record SignatureStart(String toSignHash, String digestAlgorithm, String transferFileId) {
}
