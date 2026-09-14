package com.bosch.demo.docgrounding.model;

public record DocupediaPage(
        String id,
        String title,
        String url,
        String htmlContent,
        String spaceKey,
        String lastModified
) { }
