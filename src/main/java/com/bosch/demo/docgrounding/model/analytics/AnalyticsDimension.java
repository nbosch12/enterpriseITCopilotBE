package com.bosch.demo.docgrounding.model.analytics;

import java.util.Locale;

/** What the totals are grouped by. */
public enum AnalyticsDimension {
    /** Key of qrcodeProcessingReport.plantReports. */
    PLANT("plant"),
    /** qrCodesTracking.sourceName. */
    SOURCE_NAME("sourceName"),
    /** scanlog.location.country. */
    COUNTRY("country"),
    /** qrCodesTracking.articleNumber. */
    ARTICLE_NUMBER("articleNumber"),
    /** qrCodesTracking.applicationid. */
    APPLICATION_ID("applicationId");

    private final String label;

    AnalyticsDimension(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static AnalyticsDimension from(String value) {
        if (value == null || value.isBlank()) {
            return PLANT;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT).replace(" ", "_")) {
            case "SOURCE", "SOURCENAME", "SOURCE_NAME" -> SOURCE_NAME;
            case "COUNTRY" -> COUNTRY;
            case "ARTICLE", "ARTICLENUMBER", "ARTICLE_NUMBER" -> ARTICLE_NUMBER;
            case "APPLICATION", "APPLICATIONID", "APPLICATION_ID" -> APPLICATION_ID;
            default -> PLANT;
        };
    }
}
