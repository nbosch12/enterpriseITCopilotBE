package com.bosch.demo.docgrounding.model.analytics;

import java.util.List;

/**
 * Per-day ingestion diagnostics for qrcodeProcessingReport.
 *
 * <p>This is the answer to "why does 1 August return totals but 2 August say no data": it separates
 * "the database has no document for that day" from "the document exists but the query or the parser
 * missed it".</p>
 */
public record CoverageReport(
        String collection,
        String fromDate,
        String toDate,
        int expectedDays,
        int daysWithData,
        int documentsMatchedByServerFilter,
        int documentsFoundByFallbackScan,
        int factsExtracted,
        List<String> missingDates,
        List<DayCoverage> days,
        List<DocumentProblem> problems,
        List<String> notes) {

    /** One day's ingestion outcome. */
    public record DayCoverage(
            String date,
            int documents,
            int plants,
            long received,
            long saved,
            long failed,
            List<String> plantNames) {
    }

    /** A document that could not be turned into facts, with the reason and a safe excerpt. */
    public record DocumentProblem(
            String documentId,
            String reason,
            String rawDateValue,
            String excerpt) {
    }
}
