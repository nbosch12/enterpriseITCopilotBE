package com.bosch.demo.docgrounding.model.analytics;

/**
 * One grouped row of an analytics result, e.g. plant "packit" for August 2026.
 *
 * @param key            the dimension value (plant name, sourceName, country, ...)
 * @param received       qrCodesReceived total
 * @param saved          qrCodesSaved total
 * @param failed         qrCodesFailed total
 * @param records        number of underlying records counted
 * @param daysWithData   how many distinct days contributed, so partial data is visible
 */
public record AnalyticsBucket(
        String key,
        long received,
        long saved,
        long failed,
        long records,
        int daysWithData) {

    public long metric(AnalyticsMetric metric) {
        return switch (metric) {
            case RECEIVED -> received;
            case SAVED -> saved;
            case FAILED -> failed;
            case RECORDS, SCANS -> records;
        };
    }

    public AnalyticsBucket plus(AnalyticsBucket other) {
        return new AnalyticsBucket(
                key,
                received + other.received,
                saved + other.saved,
                failed + other.failed,
                records + other.records,
                daysWithData + other.daysWithData);
    }
}
