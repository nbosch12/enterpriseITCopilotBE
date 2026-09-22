package com.bosch.demo.docgrounding.model.analytics;

import java.util.Locale;

/** Which number is ranked. */
public enum AnalyticsMetric {
    RECEIVED("qrCodesReceived"),
    SAVED("qrCodesSaved"),
    FAILED("qrCodesFailed"),
    /** Number of qrCodesTracking documents. */
    RECORDS("records"),
    /** Number of scanlog events. */
    SCANS("scans");

    private final String label;

    AnalyticsMetric(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static AnalyticsMetric from(String value) {
        if (value == null || value.isBlank()) {
            return RECEIVED;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "SAVED", "STORED", "SUCCESS" -> SAVED;
            case "FAILED", "FAILURE", "FAILURES", "ERRORS" -> FAILED;
            case "RECORDS", "RECORD", "COUNT", "QRCODES" -> RECORDS;
            case "SCANS", "SCAN" -> SCANS;
            default -> RECEIVED;
        };
    }
}
