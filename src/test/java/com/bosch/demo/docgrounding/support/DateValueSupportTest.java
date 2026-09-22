package com.bosch.demo.docgrounding.support;

import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DateValueSupportTest {

    private static final LocalDate AUG_2 = LocalDate.of(2026, 8, 2);

    @Test
    void parsesEveryStorageShapeSeenForStorageDate() {
        assertEquals(AUG_2, DateValueSupport.toLocalDate("2026-08-02").orElseThrow());
        assertEquals(AUG_2, DateValueSupport.toLocalDate("2026-08-02T23:10:00").orElseThrow());
        assertEquals(AUG_2, DateValueSupport.toLocalDate("2026-08-02T10:00:00Z").orElseThrow());
        assertEquals(AUG_2, DateValueSupport.toLocalDate("02-08-2026").orElseThrow());
        assertEquals(AUG_2, DateValueSupport.toLocalDate("02/08/2026").orElseThrow());
        assertEquals(AUG_2, DateValueSupport.toLocalDate("2026/08/02").orElseThrow());
        assertEquals(AUG_2, DateValueSupport.toLocalDate(Date.from(Instant.parse("2026-08-02T00:00:00Z"))).orElseThrow());
        assertEquals(AUG_2, DateValueSupport.toLocalDate(Instant.parse("2026-08-02T00:00:00Z").toEpochMilli()).orElseThrow());
        assertEquals(AUG_2, DateValueSupport.toLocalDate(new Document("$date", "2026-08-02T00:00:00Z")).orElseThrow());
    }

    @Test
    void unparsableValuesAreEmptyNotGuessed() {
        assertTrue(DateValueSupport.toLocalDate("not a date").isEmpty());
        assertTrue(DateValueSupport.toLocalDate(null).isEmpty());
        assertTrue(DateValueSupport.toLocalDate("").isEmpty());
    }

    @Test
    void rangeFilterCoversStringDateAndNumericStorage() {
        String json = DateValueSupport.rangeFilter("storageDate", AUG_2, AUG_2)
                .toBsonDocument(BsonDocument.class, com.mongodb.MongoClientSettings.getDefaultCodecRegistry())
                .toJson();
        assertTrue(json.contains("$or"), json);
        // exclusive upper bound of the next day so "2026-08-02T23:10:00" is still included
        assertTrue(json.contains("\"$lt\": \"2026-08-03\""), json);
        assertTrue(json.contains("$date"), json);
    }

    @Test
    void daysBetweenIsInclusive() {
        assertEquals(31, DateValueSupport.daysBetween(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)).size());
    }
}
