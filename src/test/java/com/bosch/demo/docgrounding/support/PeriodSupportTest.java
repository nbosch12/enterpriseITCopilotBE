package com.bosch.demo.docgrounding.support;

import com.bosch.demo.docgrounding.model.analytics.AnalyticsPeriod;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PeriodSupportTest {

    private static final YearMonth AUGUST_2026 = YearMonth.of(2026, 8);

    @Test
    void dayBlockWeeksAreSevenDayBlocksFromTheFirst() {
        List<AnalyticsPeriod> weeks = PeriodSupport.weeksOfMonth(AUGUST_2026, PeriodSupport.MODE_DAY_BLOCKS);
        assertEquals(5, weeks.size());
        assertEquals(LocalDate.of(2026, 8, 1), weeks.get(0).from());
        assertEquals(LocalDate.of(2026, 8, 7), weeks.get(0).to());
        assertEquals(LocalDate.of(2026, 8, 29), weeks.get(4).from());
        assertEquals(LocalDate.of(2026, 8, 31), weeks.get(4).to());
    }

    @Test
    void calendarWeeksRunMondayToSunday() {
        // 1 August 2026 is a Saturday, so calendar week 1 is just the 1st and 2nd.
        List<AnalyticsPeriod> weeks = PeriodSupport.weeksOfMonth(AUGUST_2026, PeriodSupport.MODE_CALENDAR);
        assertEquals(LocalDate.of(2026, 8, 1), weeks.get(0).from());
        assertEquals(LocalDate.of(2026, 8, 2), weeks.get(0).to());
        assertEquals(LocalDate.of(2026, 8, 3), weeks.get(1).from());
        assertEquals(LocalDate.of(2026, 8, 31), weeks.get(weeks.size() - 1).to());
    }

    @Test
    void weekNumberOfMatchesTheBlocks() {
        assertEquals(1, PeriodSupport.weekNumberOf(LocalDate.of(2026, 8, 7), PeriodSupport.MODE_DAY_BLOCKS));
        assertEquals(2, PeriodSupport.weekNumberOf(LocalDate.of(2026, 8, 8), PeriodSupport.MODE_DAY_BLOCKS));
        assertEquals("2026-08-W1", PeriodSupport.weekKey(AUGUST_2026, 1));
    }
}
