package com.bosch.demo.docgrounding.model.analytics;

import java.time.LocalDate;

/**
 * One plant's numbers for one day, normalised out of a qrcodeProcessingReport document.
 *
 * @param date           the parsed calendar day; never null for usable facts
 * @param rawDate        the value exactly as stored, kept for traceability
 * @param plant          plantReports key (also used as sourceName in this dataset)
 * @param processingTime free-text processing duration from the report, may be empty
 */
public record QrProcessingFact(
        LocalDate date,
        String rawDate,
        String plant,
        long received,
        long saved,
        long failed,
        String processingTime) {
}
