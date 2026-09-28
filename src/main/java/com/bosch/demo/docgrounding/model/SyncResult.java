package com.bosch.demo.docgrounding.model;

import java.util.List;

public record SyncResult(
        String spaceKey,
        int pagesProcessed,
        int chunksUploaded,
        String s3Prefix,
        String mode,
        String checkpointKeyUsed,
        List<String> selectedPageIds
) {
    public SyncResult(String spaceKey, int pagesProcessed, int chunksUploaded, String s3Prefix) {
        this(spaceKey, pagesProcessed, chunksUploaded, s3Prefix, "", "", List.of());
    }
}
