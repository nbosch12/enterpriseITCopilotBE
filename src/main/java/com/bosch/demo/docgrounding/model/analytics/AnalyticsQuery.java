package com.bosch.demo.docgrounding.model.analytics;

/**
 * A structured analytics request, either posted directly or parsed from a natural-language question.
 *
 * @param dimension   what to group by
 * @param metric      what to rank
 * @param period      resolved date window
 * @param descending  true for "highest", false for "lowest"
 * @param topN        how many ranked rows to return
 * @param filterKey   optional single dimension value to restrict to (e.g. one plant)
 */
public record AnalyticsQuery(
        AnalyticsDimension dimension,
        AnalyticsMetric metric,
        AnalyticsPeriod period,
        boolean descending,
        int topN,
        String filterKey,
        String originalQuestion) {
}
