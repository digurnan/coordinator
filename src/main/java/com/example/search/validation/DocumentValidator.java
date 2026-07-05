package com.example.search.validation;

import com.example.search.config.AppProperties;
import com.example.search.dto.DocumentCreateRequest;
import org.springframework.stereotype.Component;

@Component
public class DocumentValidator {

    private final int maxBodyBytes;

    public DocumentValidator(AppProperties properties) {
        this.maxBodyBytes = properties.document().maxBodyBytes();
    }

    public void validate(DocumentCreateRequest request) {
        if (request.body().getBytes().length > maxBodyBytes) {
            throw new IllegalArgumentException(
                    "Document body exceeds maximum size of " + maxBodyBytes + " bytes"
            );
        }
    }
}
