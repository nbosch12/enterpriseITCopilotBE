package com.bosch.demo.docgrounding.support;

import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads values out of QR code documents without assuming one exact schema.
 *
 * <p>The earlier code did {@code doc.get("plantReports", Document.class)} and {@code continue}d when
 * that returned null, so any document with a slightly different shape disappeared from the grounding
 * data with no trace. Here a document that cannot be interpreted is returned as an explicit problem
 * instead, so ingestion can report it.</p>
 */
public final class DocumentValueSupport {

    /** Field names seen for the report day, checked in order, dotted paths allowed. */
    public static final List<String> STORAGE_DATE_FIELDS = List.of(
            "storageDate", "_id.storageDate", "reportDate", "processingDate", "date", "day");

    /** Field names seen for the per-plant map/array. */
    public static final List<String> PLANT_REPORTS_FIELDS = List.of(
            "plantReports", "plantReport", "plants", "reports", "sourceReports");

    private static final List<String> PLANT_NAME_FIELDS = List.of(
            "plant", "plantName", "plantCode", "sourceName", "source", "name", "_id");

    private static final List<String> RECEIVED_FIELDS = List.of(
            "qrCodesReceived", "qrcodesReceived", "qrCodeReceived", "received",
            "totalReceived", "numberOfQrCodesReceived", "recordsReceived");

    private static final List<String> SAVED_FIELDS = List.of(
            "qrCodesSaved", "qrcodesSaved", "qrCodeSaved", "saved",
            "totalSaved", "numberOfQrCodesSaved", "recordsSaved", "qrCodesStored");

    private static final List<String> FAILED_FIELDS = List.of(
            "qrCodesFailed", "qrcodesFailed", "qrCodeFailed", "failed",
            "totalFailed", "numberOfQrCodesFailed", "recordsFailed", "qrCodesError");

    private DocumentValueSupport() {
    }

    /**
     * Resolve a possibly dotted path such as {@code _id.storageDate}.
     */
    public static Object valueAtPath(Document document, String path) {
        if (document == null || path == null || path.isBlank()) {
            return null;
        }
        if (!path.contains(".")) {
            return document.get(path);
        }
        Object current = document;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Document doc)) {
                return null;
            }
            current = doc.get(segment);
        }
        return current;
    }

    /** First non-null value among the candidate paths. */
    public static Object firstValue(Document document, List<String> candidatePaths) {
        for (String path : candidatePaths) {
            Object value = valueAtPath(document, path);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** First non-null value among the candidate keys of a nested document, case-insensitively. */
    public static Object firstValueLenient(Document document, List<String> candidateKeys) {
        if (document == null) {
            return null;
        }
        for (String key : candidateKeys) {
            Object value = document.get(key);
            if (value != null) {
                return value;
            }
        }
        Map<String, Object> lowered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            lowered.putIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
        }
        for (String key : candidateKeys) {
            Object value = lowered.get(key.toLowerCase(Locale.ROOT));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        String text = String.valueOf(value).trim().replace(",", "");
        if (text.isEmpty()) {
            return 0L;
        }
        try {
            return (long) Double.parseDouble(text);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    public static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Extract the per-plant rows whether {@code plantReports} is stored as a map keyed by plant name
     * or as an array of objects carrying the plant name in a field.
     */
    public static List<PlantCounts> readPlantCounts(Document document) {
        Object raw = firstValue(document, PLANT_REPORTS_FIELDS);
        List<PlantCounts> counts = new ArrayList<>();

        if (raw instanceof Document map) {
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (entry.getValue() instanceof Document plantDoc) {
                    counts.add(toCounts(entry.getKey(), plantDoc));
                } else if (entry.getValue() instanceof Number number) {
                    // Shape: { "packit": 1234 } - a bare received count.
                    counts.add(new PlantCounts(entry.getKey(), number.longValue(), 0L, 0L, ""));
                }
            }
            return counts;
        }

        if (raw instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Document plantDoc) {
                    String plant = stringValue(firstValueLenient(plantDoc, PLANT_NAME_FIELDS));
                    counts.add(toCounts(plant, plantDoc));
                }
            }
            return counts;
        }

        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() instanceof Document plantDoc) {
                    counts.add(toCounts(stringValue(entry.getKey()), plantDoc));
                }
            }
            return counts;
        }

        // No per-plant container at all: the document may itself be one plant row.
        Object receivedAtRoot = firstValueLenient(document, RECEIVED_FIELDS);
        if (receivedAtRoot != null) {
            String plant = stringValue(firstValueLenient(document, PLANT_NAME_FIELDS));
            counts.add(toCounts(plant, document));
        }
        return counts;
    }

    private static PlantCounts toCounts(String plant, Document plantDoc) {
        long received = longValue(firstValueLenient(plantDoc, RECEIVED_FIELDS));
        long saved = longValue(firstValueLenient(plantDoc, SAVED_FIELDS));
        long failed = longValue(firstValueLenient(plantDoc, FAILED_FIELDS));
        String processingTime = stringValue(firstValueLenient(plantDoc,
                List.of("processingTime", "processingDuration", "duration")));
        String normalizedPlant = plant == null || plant.isBlank() ? "unknown" : plant.trim();
        return new PlantCounts(normalizedPlant, received, saved, failed, processingTime);
    }

    /** One plant row lifted out of a daily processing report. */
    public record PlantCounts(String plant, long received, long saved, long failed, String processingTime) {
    }
}
