package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.model.analytics.AnalyticsDimension;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsMetric;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsPeriod;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsQuery;
import com.bosch.demo.docgrounding.support.PeriodSupport;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises counting/ranking questions so they can be answered from MongoDB instead of the vector
 * index.
 *
 * <p>Deliberately conservative: a question is only claimed when it contains both a ranking or
 * totalling intent <em>and</em> a resolvable period. Anything else falls through to retrieval, so
 * ordinary document questions are unaffected.</p>
 */
@Component
public class AnalyticsQuestionParser {

    private static final Map<String, Integer> MONTHS = new LinkedHashMap<>();

    static {
        MONTHS.put("january", 1);
        MONTHS.put("february", 2);
        MONTHS.put("march", 3);
        MONTHS.put("april", 4);
        MONTHS.put("may", 5);
        MONTHS.put("june", 6);
        MONTHS.put("july", 7);
        MONTHS.put("august", 8);
        MONTHS.put("september", 9);
        MONTHS.put("october", 10);
        MONTHS.put("november", 11);
        MONTHS.put("december", 12);
    }

    private static final String MONTH_ALTERNATION =
            "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?"
                    + "|aug(?:ust)?|sep(?:t|tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?";

    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b");

    private static final Pattern DAY_MONTH_YEAR = Pattern.compile(
            "\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?(" + MONTH_ALTERNATION + ")[a-z]*\\.?,?\\s+(\\d{4})\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MONTH_DAY_YEAR = Pattern.compile(
            "\\b(" + MONTH_ALTERNATION + ")[a-z]*\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MONTH_YEAR = Pattern.compile(
            "\\b(" + MONTH_ALTERNATION + ")[a-z]*\\.?,?\\s+(\\d{4})\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern YEAR_MONTH_NUMERIC = Pattern.compile("\\b(\\d{4})-(\\d{1,2})\\b");

    private static final Pattern WEEK_ORDINAL = Pattern.compile(
            "\\b(\\d)(?:st|nd|rd|th)\\s+week\\b|\\b(first|second|third|fourth|fifth)\\s+week\\b"
                    + "|\\bweek\\s*(?:number\\s*|no\\.?\\s*|#\\s*)?(\\d)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RANKING = Pattern.compile(
            "\\b(highest|most|top|maximum|max|largest|greatest|biggest|best|"
                    + "lowest|least|fewest|minimum|min|smallest|worst)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DESCENDING = Pattern.compile(
            "\\b(highest|most|top|maximum|max|largest|greatest|biggest|best)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TOTALLING = Pattern.compile(
            "\\b(total|totals|sum|how many|count|number of)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern DIMENSION_WORD = Pattern.compile(
            "\\b(plant|plants|source\\s*name|sourcename|source|country|countries|"
                    + "article\\s*number|articlenumber|application\\s*id|applicationid)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern METRIC_WORD = Pattern.compile(
            "\\b(qr\\s*codes?|qrcodes?|received|receive|recieved|saved|stored|failed|failures?|"
                    + "scans?|records?|processed)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TOP_N = Pattern.compile("\\btop\\s+(\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);

    private final Clock clock;
    private final String weekOfMonthMode;

    public AnalyticsQuestionParser() {
        this(Clock.systemDefaultZone(), PeriodSupport.MODE_DAY_BLOCKS);
    }

    public AnalyticsQuestionParser(Clock clock, String weekOfMonthMode) {
        this.clock = clock;
        this.weekOfMonthMode = weekOfMonthMode;
    }

    /**
     * @param question       the user's natural-language question
     * @param weekMode       DAY_BLOCKS or CALENDAR, from configuration
     * @return a structured query, or empty when this is not an analytics question
     */
    public Optional<AnalyticsQuery> parse(String question, String weekMode) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        String text = question.toLowerCase(Locale.ROOT);

        boolean hasRanking = RANKING.matcher(text).find();
        boolean hasTotalling = TOTALLING.matcher(text).find();
        boolean hasDimension = DIMENSION_WORD.matcher(text).find();
        boolean hasMetric = METRIC_WORD.matcher(text).find();

        if (!hasRanking && !(hasTotalling && (hasDimension || hasMetric))) {
            return Optional.empty();
        }

        Optional<AnalyticsPeriod> period = parsePeriod(text, weekMode == null ? weekOfMonthMode : weekMode);
        if (period.isEmpty()) {
            return Optional.empty();
        }

        AnalyticsDimension dimension = parseDimension(text);
        AnalyticsMetric metric = parseMetric(text, dimension);
        boolean descending = !hasRanking || DESCENDING.matcher(text).find();

        int topN = 0;
        Matcher topMatcher = TOP_N.matcher(text);
        if (topMatcher.find()) {
            topN = Integer.parseInt(topMatcher.group(1));
        }

        return Optional.of(new AnalyticsQuery(
                dimension, metric, period.get(), descending, topN, null, question));
    }

    public Optional<AnalyticsQuery> parse(String question) {
        return parse(question, weekOfMonthMode);
    }

    /**
     * True when the question ranks a known dimension but names no concrete period.
     *
     * <p>"Which plant received the highest number of records in a particular month" is a real
     * question with a missing input. Asking which month is far more useful than letting retrieval
     * invent a winner.</p>
     */
    public boolean requiresPeriodClarification(String question, String weekMode) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String text = question.toLowerCase(Locale.ROOT);
        boolean hasRanking = RANKING.matcher(text).find();
        boolean hasDimension = DIMENSION_WORD.matcher(text).find();
        if (!hasRanking || !hasDimension) {
            return false;
        }
        return parsePeriod(text, weekMode == null ? weekOfMonthMode : weekMode).isEmpty();
    }

    /**
     * True when the question is clearly a counting/ranking question over the QR code data, whether
     * or not a usable period could be extracted.
     *
     * <p>Used to tell "which plant received the most records in a particular month" (analytical, but
     * no month was actually named) apart from an ordinary document question. The first deserves a
     * request for the missing period, not a hallucinated winner from retrieval.</p>
     */
    public boolean looksAnalytical(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String text = question.toLowerCase(Locale.ROOT);
        boolean hasRanking = RANKING.matcher(text).find();
        boolean hasTotalling = TOTALLING.matcher(text).find();
        boolean hasDimension = DIMENSION_WORD.matcher(text).find();
        boolean hasMetric = METRIC_WORD.matcher(text).find();
        return (hasRanking || hasTotalling) && (hasDimension || hasMetric);
    }

    private AnalyticsDimension parseDimension(String text) {
        if (text.contains("sourcename") || text.contains("source name") || text.contains("source")) {
            return AnalyticsDimension.SOURCE_NAME;
        }
        if (text.contains("countr")) {
            return AnalyticsDimension.COUNTRY;
        }
        if (text.contains("article")) {
            return AnalyticsDimension.ARTICLE_NUMBER;
        }
        if (text.contains("applicationid") || text.contains("application id")) {
            return AnalyticsDimension.APPLICATION_ID;
        }
        return AnalyticsDimension.PLANT;
    }

    private AnalyticsMetric parseMetric(String text, AnalyticsDimension dimension) {
        if (text.contains("fail") || text.contains("error") || text.contains("reject")) {
            return AnalyticsMetric.FAILED;
        }
        if (text.contains("saved") || text.contains("stored") || text.contains("success")) {
            return AnalyticsMetric.SAVED;
        }
        if (text.contains("scan")) {
            return AnalyticsMetric.SCANS;
        }
        if (text.contains("receiv")) {
            return AnalyticsMetric.RECEIVED;
        }
        if (text.contains("track")
                || dimension == AnalyticsDimension.ARTICLE_NUMBER
                || dimension == AnalyticsDimension.APPLICATION_ID) {
            return AnalyticsMetric.RECORDS;
        }
        // "number of QR codes / records" for a plant or source means qrCodesReceived in this dataset.
        return AnalyticsMetric.RECEIVED;
    }

    /**
     * Period resolution order matters: a full date must win over the month it contains, and a week
     * phrase must win over the bare month it sits next to.
     */
    private Optional<AnalyticsPeriod> parsePeriod(String text, String weekMode) {
        Matcher isoDate = ISO_DATE.matcher(text);
        if (isoDate.find()) {
            Optional<LocalDate> date = safeDate(
                    Integer.parseInt(isoDate.group(1)),
                    Integer.parseInt(isoDate.group(2)),
                    Integer.parseInt(isoDate.group(3)));
            if (date.isPresent()) {
                return Optional.of(PeriodSupport.day(date.get()));
            }
        }

        Optional<Integer> weekNumber = parseWeekNumber(text);
        Optional<YearMonth> yearMonth = parseYearMonth(text);

        if (weekNumber.isPresent()) {
            YearMonth month = yearMonth.orElseGet(() -> YearMonth.now(clock));
            return Optional.of(PeriodSupport.weekOfMonth(month, weekNumber.get(), weekMode));
        }

        Matcher dayMonthYear = DAY_MONTH_YEAR.matcher(text);
        if (dayMonthYear.find()) {
            Optional<LocalDate> date = safeDate(
                    Integer.parseInt(dayMonthYear.group(3)),
                    monthNumber(dayMonthYear.group(2)),
                    Integer.parseInt(dayMonthYear.group(1)));
            if (date.isPresent()) {
                return Optional.of(PeriodSupport.day(date.get()));
            }
        }

        Matcher monthDayYear = MONTH_DAY_YEAR.matcher(text);
        if (monthDayYear.find()) {
            Optional<LocalDate> date = safeDate(
                    Integer.parseInt(monthDayYear.group(3)),
                    monthNumber(monthDayYear.group(1)),
                    Integer.parseInt(monthDayYear.group(2)));
            if (date.isPresent()) {
                return Optional.of(PeriodSupport.day(date.get()));
            }
        }

        if (yearMonth.isPresent()) {
            return Optional.of(PeriodSupport.month(yearMonth.get()));
        }

        LocalDate today = LocalDate.now(clock);
        if (text.contains("yesterday")) {
            return Optional.of(PeriodSupport.day(today.minusDays(1)));
        }
        if (text.contains("today")) {
            return Optional.of(PeriodSupport.day(today));
        }
        if (text.contains("last month") || text.contains("previous month")) {
            return Optional.of(PeriodSupport.month(YearMonth.from(today).minusMonths(1)));
        }
        if (text.contains("this month") || text.contains("current month")) {
            return Optional.of(PeriodSupport.month(YearMonth.from(today)));
        }
        if (text.contains("last 7 days") || text.contains("last seven days") || text.contains("past week")) {
            return Optional.of(PeriodSupport.range(today.minusDays(6), today));
        }
        if (text.contains("last 30 days") || text.contains("last thirty days")) {
            return Optional.of(PeriodSupport.range(today.minusDays(29), today));
        }
        return Optional.empty();
    }

    private Optional<Integer> parseWeekNumber(String text) {
        Matcher matcher = WEEK_ORDINAL.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        if (matcher.group(1) != null) {
            return Optional.of(Integer.parseInt(matcher.group(1)));
        }
        if (matcher.group(2) != null) {
            return Optional.of(switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "first" -> 1;
                case "second" -> 2;
                case "third" -> 3;
                case "fourth" -> 4;
                default -> 5;
            });
        }
        if (matcher.group(3) != null) {
            return Optional.of(Integer.parseInt(matcher.group(3)));
        }
        return Optional.empty();
    }

    private Optional<YearMonth> parseYearMonth(String text) {
        Matcher monthYear = MONTH_YEAR.matcher(text);
        if (monthYear.find()) {
            int month = monthNumber(monthYear.group(1));
            int year = Integer.parseInt(monthYear.group(2));
            if (month >= 1 && year >= 1970 && year <= 9999) {
                return Optional.of(YearMonth.of(year, month));
            }
        }
        Matcher numeric = YEAR_MONTH_NUMERIC.matcher(text);
        if (numeric.find()) {
            int year = Integer.parseInt(numeric.group(1));
            int month = Integer.parseInt(numeric.group(2));
            if (month >= 1 && month <= 12 && year >= 1970 && year <= 9999) {
                return Optional.of(YearMonth.of(year, month));
            }
        }
        // A bare month name with no year: assume the most recent occurrence.
        for (Map.Entry<String, Integer> entry : MONTHS.entrySet()) {
            if (text.contains(entry.getKey())) {
                LocalDate today = LocalDate.now(clock);
                YearMonth candidate = YearMonth.of(today.getYear(), entry.getValue());
                if (candidate.isAfter(YearMonth.from(today))) {
                    candidate = candidate.minusYears(1);
                }
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private int monthNumber(String token) {
        String normalized = token.toLowerCase(Locale.ROOT).replace(".", "").trim();
        for (Map.Entry<String, Integer> entry : MONTHS.entrySet()) {
            if (entry.getKey().startsWith(normalized) || normalized.startsWith(entry.getKey().substring(0, 3))) {
                return entry.getValue();
            }
        }
        return -1;
    }

    private Optional<LocalDate> safeDate(int year, int month, int day) {
        if (month < 1 || month > 12 || day < 1 || day > 31 || year < 1970 || year > 9999) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.of(year, month, day));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }
}
