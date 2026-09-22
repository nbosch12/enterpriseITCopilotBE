package com.bosch.demo.docgrounding.support;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentValueSupportTest {

    @Test
    void readsPlantReportsStoredAsMap() {
        Document doc = new Document("plantReports", new Document("packit",
                new Document("qrCodesReceived", 10).append("qrCodesSaved", 9).append("qrCodesFailed", 1)));
        List<DocumentValueSupport.PlantCounts> counts = DocumentValueSupport.readPlantCounts(doc);
        assertEquals(1, counts.size());
        assertEquals("packit", counts.get(0).plant());
        assertEquals(10, counts.get(0).received());
        assertEquals(1, counts.get(0).failed());
    }

    @Test
    void readsPlantReportsStoredAsArray() {
        Document doc = new Document("plantReports", List.of(
                new Document("plant", "packit").append("qrCodesReceived", "1,200"),
                new Document("plantName", "plantA").append("qrCodesReceived", 5)));
        List<DocumentValueSupport.PlantCounts> counts = DocumentValueSupport.readPlantCounts(doc);
        assertEquals(2, counts.size());
        assertEquals(1200, counts.get(0).received());
        assertEquals("plantA", counts.get(1).plant());
    }

    @Test
    void documentWithoutPlantDataYieldsNothingRatherThanThrowing() {
        assertTrue(DocumentValueSupport.readPlantCounts(new Document("storageDate", "2026-08-02")).isEmpty());
    }

    @Test
    void resolvesDottedPaths() {
        Document doc = new Document("_id", new Document("storageDate", "2026-08-02"));
        assertEquals("2026-08-02", DocumentValueSupport.valueAtPath(doc, "_id.storageDate"));
    }
}
