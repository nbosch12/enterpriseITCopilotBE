package com.bosch.demo.docgrounding.support;

import com.mongodb.client.model.Filters;
import org.bson.BsonType;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Date handling that tolerates the mixed representations found in the QR code collections.
 *
 * <p>The previous implementation compared {@code storageDate} as a plain string
 * ({@code $gte "2026-08-01"}). That silently returns nothing when a document stores the date as a
 * BSON {@code Date}, as epoch millis, or in a non-ISO pattern, which is why some days reported
 * totals while others reported "no data". Everything here is defensive on purpose: a date we cannot
 * classify is reported rather than dropped.</p>
 */
public final class DateValueSupport {

    private static final Pattern EPOCH_MILLIS = Pattern.compile("^-?\\d{12,14}$");
    private static final Pattern EPOCH_SECONDS = Pattern.compile("^-?\\d{9,11}$");

    /** Ordered on purpose: unambiguous ISO patterns win before locale-specific ones. */
    private static final List<DateTimeFormatter> DATE_ONLY_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,                 // 2026-08-02
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("d-M-yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"));

    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,            // 2026-08-02T10:15:30
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));

    private DateValueSupport() {
    }

    /**
     * Convert any BSON-ish value into a calendar day, or empty when it is not a date at all.
     */
    public static Optional<LocalDate> toLocalDate(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Date date) {
            return Optional.of(date.toInstant().atZone(ZoneOffset.UTC).toLocalDate());
        }
        if (value instanceof Instant instant) {
            return Optional.of(instant.atZone(ZoneOffset.UTC).toLocalDate());
        }
        if (value instanceof LocalDate localDate) {
            return Optional.of(localDate);
        }
        if (value instanceof LocalDateTime localDateTime) {
            return Optional.of(localDateTime.toLocalDate());
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return Optional.of(offsetDateTime.atZoneSameInstant(ZoneOffset.UTC).toLocalDate());
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            return Optional.of(zonedDateTime.withZoneSameInstant(ZoneOffset.UTC).toLocalDate());
        }
        if (value instanceof Number number) {
            return Optional.of(fromEpoch(number.longValue()));
        }
        if (value instanceof Document document) {
            // Extended JSON shapes such as { "$date": ... } or { "$date": { "$numberLong": "..." } }
            Object nested = document.get("$date");
            if (nested instanceof Document inner) {
                nested = inner.get("$numberLong");
            }
            return nested == null ? Optional.empty() : toLocalDate(nested);
        }
        return parseString(String.valueOf(value));
    }

    /**
     * Render any date value as {@code yyyy-MM-dd}, or an empty string when it cannot be parsed.
     */
    public static String toIsoDate(Object value) {
        return toLocalDate(value).map(LocalDate::toString).orElse("");
    }

    private static Optional<LocalDate> parseString(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        if (EPOCH_MILLIS.matcher(text).matches()) {
            return Optional.of(fromEpoch(Long.parseLong(text)));
        }
        if (EPOCH_SECONDS.matcher(text).matches()) {
            return Optional.of(fromEpoch(Long.parseLong(text) * 1000L));
        }

        // Trailing zone designators ("Z", "+02:00") are handled by the instant/offset parsers first.
        try {
            return Optional.of(Instant.parse(text).atZone(ZoneOffset.UTC).toLocalDate());
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return Optional.of(OffsetDateTime.parse(text).atZoneSameInstant(ZoneOffset.UTC).toLocalDate());
        } catch (Exception ignored) {
            // fall through
        }
        for (DateTimeFormatter formatter : DATE_TIME_FORMATS) {
            try {
                return Optional.of(LocalDateTime.parse(text, formatter).toLocalDate());
            } catch (Exception ignored) {
                // try next
            }
        }
        for (DateTimeFormatter formatter : DATE_ONLY_FORMATS) {
            try {
                return Optional.of(LocalDate.parse(text, formatter));
            } catch (Exception ignored) {
                // try next
            }
        }

        // Last resort: an ISO date hiding inside a longer string, e.g. "report-2026-08-02-final".
        int isoStart = indexOfIsoDate(text);
        if (isoStart >= 0) {
            try {
                return Optional.of(LocalDate.parse(text.substring(isoStart, isoStart + 10)));
            } catch (Exception ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static int indexOfIsoDate(String text) {
        for (int i = 0; i + 10 <= text.length(); i++) {
            if (Character.isDigit(text.charAt(i))
                    && Character.isDigit(text.charAt(i + 1))
                    && Character.isDigit(text.charAt(i + 2))
                    && Character.isDigit(text.charAt(i + 3))
                    && text.charAt(i + 4) == '-'
                    && Character.isDigit(text.charAt(i + 5))
                    && Character.isDigit(text.charAt(i + 6))
                    && text.charAt(i + 7) == '-'
                    && Character.isDigit(text.charAt(i + 8))
                    && Character.isDigit(text.charAt(i + 9))) {
                return i;
            }
        }
        return -1;
    }

    private static LocalDate fromEpoch(long value) {
        // Treat 10-digit values as seconds, anything larger as milliseconds.
        long millis = Math.abs(value) < 100_000_000_000L ? value * 1000L : value;
        return Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate();
    }

    /**
     * Build a server-side filter that matches the range regardless of how the field is stored.
     *
     * <p>Three typed branches are combined with {@code $or}:</p>
     * <ul>
     *   <li>string values compared against {@code yyyy-MM-dd} bounds, using an exclusive upper bound
     *       of {@code to + 1 day} so timestamps like {@code 2026-08-31T23:40:00} are still included;</li>
     *   <li>BSON {@code Date} values compared against real instants;</li>
     *   <li>numeric epoch values compared against millisecond bounds.</li>
     * </ul>
     */
    public static Bson rangeFilter(String field, LocalDate from, LocalDate toInclusive) {
        if (from == null && toInclusive == null) {
            return new Document();
        }

        List<Bson> stringBranch = new ArrayList<>();
        List<Bson> dateBranch = new ArrayList<>();
        List<Bson> numberBranch = new ArrayList<>();

        stringBranch.add(Filters.type(field, BsonType.STRING));
        dateBranch.add(Filters.type(field, BsonType.DATE_TIME));
        numberBranch.add(Filters.or(
                Filters.type(field, BsonType.INT64),
                Filters.type(field, BsonType.INT32),
                Filters.type(field, BsonType.DOUBLE),
                Filters.type(field, BsonType.DECIMAL128)));

        if (from != null) {
            Date fromInstant = Date.from(from.atStartOfDay(ZoneOffset.UTC).toInstant());
            stringBranch.add(Filters.gte(field, from.toString()));
            dateBranch.add(Filters.gte(field, fromInstant));
            numberBranch.add(Filters.gte(field, fromInstant.getTime()));
        }
        if (toInclusive != null) {
            LocalDate exclusiveEnd = toInclusive.plusDays(1);
            Date toInstant = Date.from(exclusiveEnd.atStartOfDay(ZoneOffset.UTC).toInstant());
            stringBranch.add(Filters.lt(field, exclusiveEnd.toString()));
            dateBranch.add(Filters.lt(field, toInstant));
            numberBranch.add(Filters.lt(field, toInstant.getTime()));
        }

        return Filters.or(
                Filters.and(stringBranch),
                Filters.and(dateBranch),
                Filters.and(numberBranch));
    }

    /** Every calendar day in the inclusive range, used for coverage reporting. */
    public static List<LocalDate> daysBetween(LocalDate from, LocalDate toInclusive) {
        List<LocalDate> days = new ArrayList<>();
        if (from == null || toInclusive == null || from.isAfter(toInclusive)) {
            return days;
        }
        for (LocalDate day = from; !day.isAfter(toInclusive); day = day.plusDays(1)) {
            days.add(day);
        }
        return days;
    }
}
