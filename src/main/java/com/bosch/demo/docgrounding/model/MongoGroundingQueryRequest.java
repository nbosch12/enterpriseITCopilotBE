package com.bosch.demo.docgrounding.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Curated Mongo query request. queryType intentionally selects a server-side query template;
 * raw Mongo filters/pipelines are not accepted from callers.
 *
 * @param includeRollups   for PROCESSING_SUMMARY, also upload pre-computed monthly/weekly/daily
 *                         aggregates so ranking questions can be answered from a single chunk
 * @param replaceExisting  delete the deterministic prefix before writing, to drop records that no
 *                         longer exist in the source
 */
public record MongoGroundingQueryRequest(
        @NotBlank String queryType,
        String fromDate,
        String toDate,
        String plant,
        String uuid,
        String articleNumber,
        String applicationId,
        String sourceName,
        String country,
        Integer limit,
        Boolean includeRollups,
        Boolean replaceExisting
) {
    /** Kept so existing callers using the original ten-field shape still compile. */
    public MongoGroundingQueryRequest(
            String queryType,
            String fromDate,
            String toDate,
            String plant,
            String uuid,
            String articleNumber,
            String applicationId,
            String sourceName,
            String country,
            Integer limit) {
        this(queryType, fromDate, toDate, plant, uuid, articleNumber, applicationId,
                sourceName, country, limit, null, null);
    }
}
