package com.bosch.demo.docgrounding.model;

import jakarta.validation.constraints.NotBlank;

public record VectorAskRequest(
        @NotBlank String question,
        String repositoryId,
        String s3Prefix,
        Integer maxChunks,
        Integer topK
) { }

