package com.bosch.demo.docgrounding.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Curated Mongo query request. queryType intentionally selects a server-side query template;
 * raw Mongo filters/pipelines are not accepted from callers.
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
        Integer limit
) { }
