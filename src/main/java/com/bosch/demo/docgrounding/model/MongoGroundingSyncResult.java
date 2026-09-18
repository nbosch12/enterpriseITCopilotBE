package com.bosch.demo.docgrounding.model;

import java.util.List;

public record MongoGroundingSyncResult(
        String queryId,
        String queryType,
        int recordsFound,
        int chunksUploaded,
        String s3Prefix,
        String groundingIncludePath,
        List<String> collections
) { }
