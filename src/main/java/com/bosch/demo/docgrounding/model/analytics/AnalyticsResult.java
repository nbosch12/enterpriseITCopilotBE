package com.bosch.demo.docgrounding.model.analytics;

import java.util.List;

/**
 * Deterministic answer to an analytics question.
 *
 * <p>{@code answer} is a ready-to-display sentence; {@code ranked} carries the full ordered
 * breakdown so a UI can show the supporting table. {@code missingDates} and
 * {@code unparsableDateSamples} deliberately travel with the answer: a total computed over an
 * incomplete range should never look authoritative.</p>
 */
public record AnalyticsResult(
        String question,
        AnalyticsDimension dimension,
        AnalyticsMetric metric,
        AnalyticsPeriod period,
        AnalyticsDataSource dataSource,
        List<AnalyticsBucket> ranked,
        AnalyticsBucket top,
        long total,
        int documentsScanned,
        int daysCovered,
        List<String> missingDates,
        List<String> notes,
        String answer) {
}
