package com.lacuna.web;

import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * The largest file the server accepts ({@code spring.servlet.multipart.max-file-size}), so upload forms can state it
 * and leave out larger files before sending: the server refuses the whole request at the first one. Templates read it
 * as {@code @uploadLimits.maxFileSize}, which, unlike a model attribute, is also there in views rendered by exception
 * handlers.
 */
@Component
public class UploadLimits {

    private final DataSize maxFileSize;

    public UploadLimits(MultipartProperties multipart) {
        this.maxFileSize = multipart.getMaxFileSize();
    }

    public DataSize getMaxFileSize() {
        return maxFileSize;
    }
}
