package com.bosch.demo.docgrounding.model;

public record DocumentChunk(
        String pageId,
        String pageTitle,
        String sourceUrl,
        String spaceKey,
        int chunkIndex,
        String text
) { }
