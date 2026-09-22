package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.model.analytics.AnalyticsDimension;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsGranularity;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsMetric;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsQuery;
import com.bosch.demo.docgrounding.support.PeriodSupport;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticsQuestionParserTest {

    private final AnalyticsQuestionParser parser = new AnalyticsQuestionParser(
            Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC), PeriodSupport.MODE_DAY_BLOCKS);

    private AnalyticsQuery parse(String question) {
        return parser.parse(question).orElseThrow(() -> new AssertionError("not recognised: " + question));
    }

    @Test
    void monthQuestionWithSourceNameAndTypo() {
        AnalyticsQuery q = parse("Which sourceName recieved the highest number of qrcode in August 2026");
        assertEquals(AnalyticsDimension.SOURCE_NAME, q.dimension());
        assertEquals(AnalyticsMetric.RECEIVED, q.metric());
        assertEquals(AnalyticsGranularity.MONTH, q.period().granularity());
        assertEquals(LocalDate.of(2026, 8, 1), q.period().from());
        assertEquals(LocalDate.of(2026, 8, 31), q.period().to());
        assertTrue(q.descending());
    }

    @Test
    void weekOfMonthInSeveralPhrasings() {
        for (String phrasing : new String[]{"1st week of August 2026", "first week of August 2026", "week 1 of August 2026"}) {
            AnalyticsQuery q = parse("which plant received the highest number of records in " + phrasing);
            assertEquals(AnalyticsGranularity.WEEK_OF_MONTH, q.period().granularity(), phrasing);
            assertEquals(LocalDate.of(2026, 8, 1), q.period().from(), phrasing);
            assertEquals(LocalDate.of(2026, 8, 7), q.period().to(), phrasing);
        }
        assertEquals(LocalDate.of(2026, 8, 15), parse("top plant in the 3rd week of August 2026").period().from());
    }

    @Test
    void singleDayInSeveralPhrasings() {
        for (String phrasing : new String[]{"on 2 August 2026", "on 2nd of August 2026", "on August 2, 2026", "on 2026-08-02"}) {
            AnalyticsQuery q = parse("which plant received the highest number of records " + phrasing);
            assertEquals(AnalyticsGranularity.DAY, q.period().granularity(), phrasing);
            assertEquals(LocalDate.of(2026, 8, 2), q.period().from(), phrasing);
        }
    }

    @Test
    void lowestAndFailedAreRecognised() {
        AnalyticsQuery q = parse("which plant had the lowest number of failed qr codes in August 2026");
        assertEquals(AnalyticsMetric.FAILED, q.metric());
        assertFalse(q.descending());
    }

    @Test
    void relativePeriodsResolveAgainstTheClock() {
        assertEquals(LocalDate.of(2026, 8, 1), parse("which plant received the most records last month").period().from());
    }

    @Test
    void documentQuestionsAreLeftToVectorSearch() {
        assertTrue(parser.parse("How does the reporting microservice work?").isEmpty());
        assertTrue(parser.parse("Explain the S/4 HANA migration steps").isEmpty());
    }

    @Test
    void rankingQuestionWithoutConcretePeriodAsksForOne() {
        String question = "which plant received highest number of records in a particular month";
        assertTrue(parser.parse(question).isEmpty());
        assertTrue(parser.requiresPeriodClarification(question, PeriodSupport.MODE_DAY_BLOCKS));
        assertFalse(parser.requiresPeriodClarification("How does the reporting microservice work?", null));
    }
}
