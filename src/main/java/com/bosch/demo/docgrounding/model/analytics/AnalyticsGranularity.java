package com.bosch.demo.docgrounding.model.analytics;

import java.util.Locale;

/** Time bucket an analytics question is asked over. */
public enum AnalyticsGranularity {
    DAY,
    WEEK_OF_MONTH,
    MONTH,
    RANGE;

    public static AnalyticsGranularity from(String value) {
        if (value == null || value.isBlank()) {
            return RANGE;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "DAY", "DAILY", "DATE" -> DAY;
            case "WEEK", "WEEKLY", "WEEK_OF_MONTH", "WEEKOFMONTH" -> WEEK_OF_MONTH;
            case "MONTH", "MONTHLY" -> MONTH;
            default -> RANGE;
        };
    }
}
