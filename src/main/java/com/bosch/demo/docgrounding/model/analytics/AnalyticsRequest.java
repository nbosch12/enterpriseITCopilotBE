package com.bosch.demo.docgrounding.model.analytics;

/**
 * Structured ranking request for {@code POST /api/mongodb/analytics/rank}.
 *
 * <p>Period resolution, first match wins:</p>
 * <ol>
 *   <li>{@code date} (yyyy-MM-dd) - a single day</li>
 *   <li>{@code month} (yyyy-MM) plus {@code weekOfMonth} (1-5) - one week of that month</li>
 *   <li>{@code month} (yyyy-MM) - the whole month</li>
 *   <li>{@code fromDate} and {@code toDate} (yyyy-MM-dd) - an arbitrary inclusive range</li>
 * </ol>
 *
 * @param dimension PLANT (default), SOURCE_NAME, COUNTRY, ARTICLE_NUMBER, APPLICATION_ID
 * @param metric    RECEIVED (default), SAVED, FAILED, RECORDS, SCANS
 * @param order     "desc" (default, highest first) or "asc"
 * @param filterKey restrict to one dimension value, e.g. a single plant
 */
public record AnalyticsRequest(
        String dimension,
        String metric,
        String date,
        String month,
        Integer weekOfMonth,
        String fromDate,
        String toDate,
        String order,
        Integer topN,
        String filterKey) {
}
