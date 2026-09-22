package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.config.AppProperties;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsDataSource;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsDimension;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsMetric;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsQuery;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsResult;
import com.bosch.demo.docgrounding.model.analytics.QrProcessingFact;
import com.bosch.demo.docgrounding.support.PeriodSupport;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ranking over a full month where the winner changes by period: plantB leads week 1, plantA leads
 * 2 August, packit leads the month. A top-k retriever shown a few days would pick any of them.
 */
class QrCodeAnalyticsServiceTest {

    private static final int MISSING_DAY = 20;

    private static long received(String plant, int day) {
        return switch (plant) {
            case "packit" -> day <= 7 ? 500 : 1000 + 10L * day;
            case "plantA" -> day == 2 ? 5000 : 700;
            default -> day <= 7 ? 1500 : 300;
        };
    }

    private final QrCodeAnalyticsService service = new QrCodeAnalyticsService(new FakeRepository(), new AppProperties());

    private AnalyticsQuery query(AnalyticsDimension dimension, com.bosch.demo.docgrounding.model.analytics.AnalyticsPeriod period) {
        return new AnalyticsQuery(dimension, AnalyticsMetric.RECEIVED, period, true, 0, null, "test");
    }

    @Test
    void monthWinnerUsesEveryDayNotASample() {
        AnalyticsResult result = service.rank(query(AnalyticsDimension.PLANT, PeriodSupport.month(YearMonth.of(2026, 8))));
        assertEquals("packit", result.top().key());
        assertEquals(30980, result.top().received());
        assertEquals(30, result.daysCovered());
        assertEquals(List.of("2026-08-20"), result.missingDates());
    }

    @Test
    void weekOfMonthWinner() {
        AnalyticsResult result = service.rank(query(AnalyticsDimension.PLANT,
                PeriodSupport.weekOfMonth(YearMonth.of(2026, 8), 1, PeriodSupport.MODE_DAY_BLOCKS)));
        assertEquals("plantB", result.top().key());
        assertEquals(10500, result.top().received());
    }

    @Test
    void singleDayWinner() {
        AnalyticsResult result = service.rank(query(AnalyticsDimension.PLANT, PeriodSupport.day(LocalDate.of(2026, 8, 2))));
        assertEquals("plantA", result.top().key());
        assertTrue(result.answer().contains("on 2026-08-02"), result.answer());
    }

    @Test
    void receivedBySourceNameIsReadFromTheProcessingReport() {
        AnalyticsResult result = service.rank(query(AnalyticsDimension.SOURCE_NAME, PeriodSupport.month(YearMonth.of(2026, 8))));
        assertEquals(AnalyticsDataSource.PROCESSING_REPORT, result.dataSource());
        assertEquals("packit", result.top().key());
    }

    @Test
    void rollupsCoverDaysWeeksAndMonth() {
        List<QrCodeAnalyticsService.Rollup> rollups =
                service.buildProcessingRollups(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        assertEquals(30, rollups.stream().filter(r -> r.kind().equals("daily")).count());
        assertEquals(5, rollups.stream().filter(r -> r.kind().equals("weekly")).count());
        QrCodeAnalyticsService.Rollup month = rollups.stream().filter(r -> r.kind().equals("monthly")).findFirst().orElseThrow();
        assertEquals("packit", month.buckets().get(0).key());
    }

    private static final class FakeRepository extends QrCodeFactRepository {
        FakeRepository() {
            super(null, new AppProperties());
        }

        @Override
        public ProcessingScan scanProcessingReports(LocalDate from, LocalDate to) {
            List<QrProcessingFact> facts = new ArrayList<>();
            for (int day = 1; day <= 31; day++) {
                LocalDate date = LocalDate.of(2026, 8, day);
                if (day == MISSING_DAY || date.isBefore(from) || date.isAfter(to)) {
                    continue;
                }
                for (String plant : new String[]{"packit", "plantA", "plantB"}) {
                    long r = received(plant, day);
                    facts.add(new QrProcessingFact(date, date.toString(), plant, r, r - 1, 1, "1s"));
                }
            }
            return new ProcessingScan(facts, facts.size() / 3, facts.size() / 3, 0, List.of(), List.of());
        }
    }
}
