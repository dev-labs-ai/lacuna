package com.lacuna.document;

public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(String id) {
        super("Documento não encontrado: " + id);
    }
}
