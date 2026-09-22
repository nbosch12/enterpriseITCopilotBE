package com.bosch.demo.docgrounding.model.analytics;

import java.time.LocalDate;

/**
 * A resolved, inclusive date window plus a human label such as "week 1 of August 2026".
 */
public record AnalyticsPeriod(
        AnalyticsGranularity granularity,
        String label,
        LocalDate from,
        LocalDate to) {

    public boolean contains(LocalDate date) {
        return date != null && !date.isBefore(from) && !date.isAfter(to);
    }

    public int days() {
        return (int) (to.toEpochDay() - from.toEpochDay()) + 1;
    }
}
