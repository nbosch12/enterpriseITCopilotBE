package com.bosch.demo.docgrounding.support;

import com.bosch.demo.docgrounding.model.analytics.AnalyticsGranularity;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsPeriod;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns "August 2026" / "1st week of August 2026" / "2 August 2026" into concrete date ranges.
 *
 * <p>Two conventions exist for "week of the month" and people mean different things by them, so the
 * mode is configurable:</p>
 * <ul>
 *   <li>{@code DAY_BLOCKS} (default) - week 1 is days 1-7, week 2 is 8-14, and so on. This is what
 *       most people mean by "the first week of August".</li>
 *   <li>{@code CALENDAR} - weeks run Monday to Sunday; week 1 is the (possibly partial) week that
 *       contains the 1st of the month.</li>
 * </ul>
 */
public final class PeriodSupport {

    public static final String MODE_DAY_BLOCKS = "DAY_BLOCKS";
    public static final String MODE_CALENDAR = "CALENDAR";

    private PeriodSupport() {
    }

    public static AnalyticsPeriod month(YearMonth yearMonth) {
        LocalDate start = yearMonth.atDay(1);
        LocalDate end = yearMonth.atEndOfMonth();
        String label = yearMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + yearMonth.getYear();
        return new AnalyticsPeriod(AnalyticsGranularity.MONTH, label, start, end);
    }

    public static AnalyticsPeriod day(LocalDate date) {
        String label = date.toString();
        return new AnalyticsPeriod(AnalyticsGranularity.DAY, label, date, date);
    }

    public static AnalyticsPeriod range(LocalDate from, LocalDate to) {
        String label = from + " to " + to;
        return new AnalyticsPeriod(AnalyticsGranularity.RANGE, label, from, to);
    }

    /**
     * Week {@code weekNumber} (1-based) of the given month, clamped to the month's real boundaries.
     */
    public static AnalyticsPeriod weekOfMonth(YearMonth yearMonth, int weekNumber, String mode) {
        List<AnalyticsPeriod> weeks = weeksOfMonth(yearMonth, mode);
        if (weeks.isEmpty()) {
            return month(yearMonth);
        }
        int index = Math.max(1, weekNumber) - 1;
        if (index >= weeks.size()) {
            index = weeks.size() - 1;
        }
        return weeks.get(index);
    }

    /**
     * All weeks of a month under the configured convention, in order.
     */
    public static List<AnalyticsPeriod> weeksOfMonth(YearMonth yearMonth, String mode) {
        List<AnalyticsPeriod> weeks = new ArrayList<>();
        LocalDate firstDay = yearMonth.atDay(1);
        LocalDate lastDay = yearMonth.atEndOfMonth();
        String monthLabel = yearMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                + " " + yearMonth.getYear();

        if (MODE_CALENDAR.equalsIgnoreCase(mode)) {
            LocalDate cursor = firstDay;
            int weekNumber = 1;
            while (!cursor.isAfter(lastDay)) {
                LocalDate weekEnd = cursor.with(DayOfWeek.SUNDAY);
                if (weekEnd.isBefore(cursor)) {
                    weekEnd = cursor;
                }
                if (weekEnd.isAfter(lastDay)) {
                    weekEnd = lastDay;
                }
                weeks.add(new AnalyticsPeriod(
                        AnalyticsGranularity.WEEK_OF_MONTH,
                        "week " + weekNumber + " of " + monthLabel,
                        cursor,
                        weekEnd));
                cursor = weekEnd.plusDays(1);
                weekNumber++;
            }
            return weeks;
        }

        int weekNumber = 1;
        for (int startDay = 1; startDay <= lastDay.getDayOfMonth(); startDay += 7) {
            int endDay = Math.min(startDay + 6, lastDay.getDayOfMonth());
            weeks.add(new AnalyticsPeriod(
                    AnalyticsGranularity.WEEK_OF_MONTH,
                    "week " + weekNumber + " of " + monthLabel,
                    yearMonth.atDay(startDay),
                    yearMonth.atDay(endDay)));
            weekNumber++;
        }
        return weeks;
    }

    /**
     * Which week of the month a date falls in, under the configured convention. 1-based.
     */
    public static int weekNumberOf(LocalDate date, String mode) {
        List<AnalyticsPeriod> weeks = weeksOfMonth(YearMonth.from(date), mode);
        for (int i = 0; i < weeks.size(); i++) {
            if (weeks.get(i).contains(date)) {
                return i + 1;
            }
        }
        return 1;
    }

    /** Stable identifier used in S3 keys, e.g. {@code 2026-08-W1}. */
    public static String weekKey(YearMonth yearMonth, int weekNumber) {
        return "%04d-%02d-W%d".formatted(yearMonth.getYear(), yearMonth.getMonthValue(), weekNumber);
    }
}
