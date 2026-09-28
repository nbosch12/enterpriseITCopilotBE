package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.config.AppProperties;
import com.bosch.demo.docgrounding.support.DateValueSupport;
import com.bosch.demo.docgrounding.support.DocumentValueSupport;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how Cosmos DB documents are mapped into the semantic text the model is grounded on.
 *
 * <p>These run against real {@code org.bson.Document} instances, so the mapping is exercised as it
 * behaves in production without needing a database.</p>
 */
class CosmosMongoGroundingServiceMappingTest {

    private CosmosMongoGroundingService service;

    @BeforeEach
    void setUp() {
        // The mapping methods use none of the collaborators, so this instance never touches a
        // database, the object store or the analytics service.
        service = new CosmosMongoGroundingService(null, null, null, null, null, new AppProperties());
    }

    // -------------------------------------------------------------------------
    // scanlog
    // -------------------------------------------------------------------------

    @Test
    void scanDocumentIsMappedToLabelledFacts() {
        Document scan = new Document("uuid", "65de2ff5-4ec9-4d0b-846c-34a0fcce1456")
                .append("scanDate", "2026-07-04T10:15:00")
                .append("application", "qr-portal")
                .append("client", new Document("userAgent", "Mozilla/5.0")
                        .append("language", "de-DE")
                        .append("operatingSystem", "Windows"))
                .append("location", new Document("country", "Germany")
                        .append("countryCode", "DE")
                        .append("city", "Stuttgart")
                        .append("timezone", "Europe/Berlin"));

        var record = service.toScanRecord(scan);

        assertEquals("scanlog", record.collection());
        assertTrue(record.content().contains("uuid: 65de2ff5-4ec9-4d0b-846c-34a0fcce1456"));
        assertTrue(record.content().contains("scanDate: 2026-07-04T10:15:00"));
        assertTrue(record.content().contains("country: Germany"));
        assertTrue(record.content().contains("city: Stuttgart"));
        assertTrue(record.content().contains("collection: scanlog"),
                "the model should be able to see which collection a fact came from");
    }

    @Test
    void personalFieldsAreNotCopiedIntoGroundingText() {
        Document scan = new Document("uuid", "abc")
                .append("scanDate", "2026-07-04T10:15:00")
                .append("hashedIP", "9f86d081884c7d65")
                .append("user", "prasain");

        String content = service.toScanRecord(scan).content();

        assertFalse(content.contains("9f86d081884c7d65"), "hashed IP must stay out of the prompt");
        assertFalse(content.contains("prasain"), "the user field must stay out of the prompt");
    }

    @Test
    void scanRecordKeyIsDerivedFromTheScanDay() {
        Document scan = new Document("uuid", "abc").append("scanDate", "2026-07-04T10:15:00");
        assertTrue(service.toScanRecord(scan).recordKey().startsWith("2026-07-04/"),
                "records should key on their day so repeated syncs overwrite rather than duplicate");
    }

    @Test
    void missingScanDateStillProducesAUsableKey() {
        Document scan = new Document("uuid", "abc");
        assertTrue(service.toScanRecord(scan).recordKey().startsWith("unknown-date/"));
    }

    // -------------------------------------------------------------------------
    // qrCodesTracking
    // -------------------------------------------------------------------------

    @Test
    void trackingDocumentIsMappedToLabelledFacts() {
        Document tracking = new Document("_id", "65de2ff5")
                .append("qrcodeContent", "https://qr.bosch.com/65de2ff5")
                .append("fqdn", "qr.bosch.com")
                .append("articleNumber", "0204114896EE9")
                .append("packagingDate", "2026-08-02")
                .append("applicationid", "00136849")
                .append("labellingLevel", "ITEM")
                .append("countChildLevelItems", 12)
                .append("sourceName", "packit")
                .append("numberOfScans", 3)
                .append("lastModifiedDate", "2026-08-03T09:00:00");

        var record = service.toTrackingRecord(tracking);

        assertEquals("qrCodesTracking", record.collection());
        assertTrue(record.content().contains("articleNumber: 0204114896EE9"));
        assertTrue(record.content().contains("sourceName: packit"));
        assertTrue(record.content().contains("numberOfScans: 3"));
        assertTrue(record.content().contains("applicationId: 00136849"),
                "the stored 'applicationid' field should be labelled readably for the model");
    }

    @Test
    void absentTrackingFieldsBecomeEmptyRatherThanTheStringNull() {
        Document sparse = new Document("_id", "65de2ff5").append("sourceName", "packit");

        String content = service.toTrackingRecord(sparse).content();

        assertFalse(content.contains("null"),
                "a literal 'null' in the context invites the model to treat it as a value");
    }

    // -------------------------------------------------------------------------
    // Date handling, which is what made whole days disappear before
    // -------------------------------------------------------------------------

    @Test
    void storageDatesAreUnderstoodInEveryShapeTheCollectionsUse() {
        LocalDate expected = LocalDate.of(2026, 8, 2);

        assertEquals(expected, DateValueSupport.toLocalDate("2026-08-02").orElseThrow());
        assertEquals(expected, DateValueSupport.toLocalDate("2026-08-02T23:10:00").orElseThrow());
        assertEquals(expected, DateValueSupport.toLocalDate("02-08-2026").orElseThrow());
        assertEquals(expected, DateValueSupport.toLocalDate(
                Date.from(Instant.parse("2026-08-02T00:00:00Z"))).orElseThrow());
        assertEquals(expected, DateValueSupport.toLocalDate(
                Instant.parse("2026-08-02T00:00:00Z").toEpochMilli()).orElseThrow());
    }

    @Test
    void anUnparsableDateIsReportedRatherThanGuessed() {
        assertTrue(DateValueSupport.toLocalDate("not a date").isEmpty());
        assertTrue(DateValueSupport.toLocalDate(null).isEmpty());
    }

    @Test
    void plantCountsAreReadWhetherStoredAsAMapOrAnArray() {
        Document asMap = new Document("plantReports", new Document("packit",
                new Document("qrCodesReceived", 1200).append("qrCodesSaved", 1190).append("qrCodesFailed", 10)));
        List<DocumentValueSupport.PlantCounts> fromMap = DocumentValueSupport.readPlantCounts(asMap);
        assertEquals(1, fromMap.size());
        assertEquals("packit", fromMap.get(0).plant());
        assertEquals(1200, fromMap.get(0).received());

        Document asArray = new Document("plantReports", List.of(
                new Document("plant", "packit").append("qrCodesReceived", 1200),
                new Document("plantName", "plantA").append("qrCodesReceived", 800)));
        List<DocumentValueSupport.PlantCounts> fromArray = DocumentValueSupport.readPlantCounts(asArray);
        assertEquals(2, fromArray.size());
        assertEquals(800, fromArray.get(1).received());
    }

    @Test
    void aDocumentWithNoPlantDataYieldsNothingInsteadOfThrowing() {
        assertTrue(DocumentValueSupport.readPlantCounts(
                new Document("storageDate", "2026-08-02")).isEmpty());
    }
}
