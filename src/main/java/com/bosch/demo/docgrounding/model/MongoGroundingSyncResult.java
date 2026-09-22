package com.bosch.demo.docgrounding.model;

import com.bosch.demo.docgrounding.model.analytics.CoverageReport;

import java.util.List;

/**
 * Outcome of preparing MongoDB data for grounding.
 *
 * @param s3Prefix             deterministic prefix; re-running a sync overwrites the same keys
 *                             instead of creating a new UUID folder, so the vector index never ends
 *                             up holding several copies of the same day
 * @param repositoryId         the single vector repository this data belongs to
 * @param rollupChunksUploaded pre-computed monthly/weekly/daily aggregate chunks
 * @param coverage             per-day ingestion report; null when not applicable to the query type
 */
public record MongoGroundingSyncResult(
        String queryId,
        String queryType,
        int recordsFound,
        int chunksUploaded,
        String s3Prefix,
        String groundingIncludePath,
        List<String> collections,
        String repositoryId,
        int rollupChunksUploaded,
        CoverageReport coverage,
        List<String> notes
) {
    /** Kept so existing callers using the original seven-field shape still compile. */
    public MongoGroundingSyncResult(
            String queryId,
            String queryType,
            int recordsFound,
            int chunksUploaded,
            String s3Prefix,
            String groundingIncludePath,
            List<String> collections) {
        this(queryId, queryType, recordsFound, chunksUploaded, s3Prefix, groundingIncludePath,
                collections, "", 0, null, List.of());
    }
}
