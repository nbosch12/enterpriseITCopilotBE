package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.config.AppProperties;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsBucket;
import com.mongodb.client.model.Filters;
import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tracking date filter has to cope with documents that carry no usable primary date.
 *
 * <p>A document whose {@code packagingDate} is absent falls back to {@code lastModifiedDate}. So
 * does one where the field is present but null: MongoDB counts a null-valued field as existing, so
 * an {@code $exists} check alone would leave those documents matching neither branch and silently
 * drop them from the totals.</p>
 */
class TrackingDateFilterTest {

    /** Renders the filter the repository builds, so the query shape itself can be asserted. */
    private String filterJson(String primary, String fallback) {
        AppProperties properties = new AppProperties();
        properties.getAnalytics().setTrackingDateField(primary);
        properties.getAnalytics().setTrackingFallbackDateField(fallback);

        QrCodeFactRepository repository = new TestRepository(properties);
        return repository.trackingDateFilterForTest(
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31))
                .toBsonDocument(BsonDocument.class,
                        com.mongodb.MongoClientSettings.getDefaultCodecRegistry())
                .toJson();
    }

    @Test
    void anAbsentPrimaryDateFallsBackToTheSecondaryField() {
        String json = filterJson("packagingDate", "lastModifiedDate");
        assertTrue(json.contains("\"$exists\": false"), "absent-field branch missing: " + json);
        assertTrue(json.contains("lastModifiedDate"), "fallback field missing: " + json);
    }

    @Test
    void aNullPrimaryDateAlsoFallsBackInsteadOfDroppingTheRecord() {
        String json = filterJson("packagingDate", "lastModifiedDate");
        // A null-valued field "exists" in MongoDB, so it needs its own branch.
        assertTrue(json.contains("\"packagingDate\": null"),
                "null-valued primary dates would be dropped: " + json);
    }

    @Test
    void noFallbackConfiguredMeansASinglePlainRangeFilter() {
        String json = filterJson("lastModifiedDate", "");
        assertFalse(json.contains("\"$exists\""),
                "without a fallback there is nothing to fall back to: " + json);
    }

    @Test
    void aFallbackEqualToThePrimaryIsIgnored() {
        String json = filterJson("packagingDate", "packagingDate");
        assertFalse(json.contains("\"$exists\""), "a self-referential fallback adds nothing: " + json);
    }

    /** Exposes the private filter builder without opening a database connection. */
    private static final class TestRepository extends QrCodeFactRepository {
        TestRepository(AppProperties properties) {
            super(null, properties);
        }
    }
}
