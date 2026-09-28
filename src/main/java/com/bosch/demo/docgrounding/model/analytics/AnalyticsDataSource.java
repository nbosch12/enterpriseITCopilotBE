package com.bosch.demo.docgrounding.model.analytics;

/** Collection the numbers were actually read from, surfaced so answers are auditable. */
public enum AnalyticsDataSource {
    PROCESSING_REPORT("qrcodeProcessingReport"),
    TRACKING("qrCodesTracking"),
    SCANLOG("scanlog");

    private final String collection;

    AnalyticsDataSource(String collection) {
        this.collection = collection;
    }

    public String collection() {
        return collection;
    }
}
