package com.bosch.demo.docgrounding.model;

public record VectorMatch(
        String key,
        String pageId,
        String pageTitle,
        String sourceUrl,
        int chunkIndex,
        double score,
        String excerpt
) { }

