package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.model.ExceptionLogEntry;
import com.bosch.demo.docgrounding.model.LogQueryResult;
import com.bosch.demo.docgrounding.model.NormalizedLogContext;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class LogNormalizerService {

    private static final int MAX_PATTERNS = 5;
    private static final int MAX_RECENT_EVENTS = 8;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public NormalizedLogContext normalize(LogQueryResult logQueryResult) {
        if (logQueryResult == null) {
            return new NormalizedLogContext(
                    "Unknown period",
                    0,
                    List.of(),
                    List.of("No log data available"),
                    List.of(),
                    "No log data was available for analysis.");
        }

        List<ExceptionLogEntry> latestExceptions = logQueryResult.getLatestExceptions() == null
                ? List.of()
                : logQueryResult.getLatestExceptions();

        List<String> impactedApps = extractImpactedApps(logQueryResult.getCountByApp());
        List<String> topPatterns = extractTopPatterns(latestExceptions);
        List<String> recentEvents = latestExceptions.stream()
                .limit(MAX_RECENT_EVENTS)
                .map(this::formatEvent)
                .toList();

        String normalizedText = buildNormalizedText(logQueryResult, impactedApps, topPatterns, recentEvents);

        return new NormalizedLogContext(
                safeQueryPeriod(logQueryResult.getQueryPeriod()),
                logQueryResult.getTotalCount(),
                impactedApps,
                topPatterns,
                recentEvents,
                normalizedText);
    }

    private List<String> extractImpactedApps(Map<String, Long> countByApp) {
        if (countByApp == null || countByApp.isEmpty()) {
            return List.of();
        }

        return countByApp.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                .toList();
    }

    private List<String> extractTopPatterns(List<ExceptionLogEntry> latestExceptions) {
        if (latestExceptions == null || latestExceptions.isEmpty()) {
            return List.of("No matching exceptions found in the selected time window");
        }

        Map<String, Long> groupedPatterns = latestExceptions.stream()
                .map(ExceptionLogEntry::getMessage)
                .filter(Objects::nonNull)
                .map(this::fingerprintMessage)
                .collect(Collectors.groupingBy(
                        pattern -> pattern,
                        LinkedHashMap::new,
                        Collectors.counting()));

        return groupedPatterns.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(MAX_PATTERNS)
                .map(entry -> entry.getKey() + " [count=" + entry.getValue() + "]")
                .toList();
    }

    private String buildNormalizedText(
            LogQueryResult logQueryResult,
            List<String> impactedApps,
            List<String> topPatterns,
            List<String> recentEvents) {

        StringBuilder builder = new StringBuilder();
        builder.append("Query period: ")
                .append(safeQueryPeriod(logQueryResult.getQueryPeriod()))
                .append("\n");
        builder.append("Total matching log entries: ")
                .append(logQueryResult.getTotalCount())
                .append("\n");

        if (!impactedApps.isEmpty()) {
            builder.append("Impacted apps:\n");
            impactedApps.forEach(app -> builder.append("- ").append(app).append("\n"));
        }

        if (!topPatterns.isEmpty()) {
            builder.append("Recurring patterns:\n");
            topPatterns.forEach(pattern -> builder.append("- ").append(pattern).append("\n"));
        }

        if (!recentEvents.isEmpty()) {
            builder.append("Recent events:\n");
            recentEvents.forEach(event -> builder.append("- ").append(event).append("\n"));
        }

        if (logQueryResult.isHasErrors() && logQueryResult.getErrorMessage() != null) {
            builder.append("Query warning: ")
                    .append(logQueryResult.getErrorMessage())
                    .append("\n");
        }

        return builder.toString().trim();
    }

    private String formatEvent(ExceptionLogEntry entry) {
        String timestamp = entry.getTimeGenerated() == null
                ? "unknown-time"
                : entry.getTimeGenerated().format(TIME_FORMATTER);

        return timestamp
                + " | app=" + safe(entry.getAppRoleName())
                + " | severity=" + entry.getSeverityLevel()
                + " | operation=" + safe(entry.getOperationName())
                + " | message=" + abbreviate(safe(entry.getMessage()), 220);
    }

    private String fingerprintMessage(String message) {
        String normalized = safe(message)
                .replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "{uuid}")
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("\\b\\d+\\b", "{n}")
                .trim();

        if (normalized.isBlank()) {
            return "Empty log message";
        }

        return abbreviate(normalized, 140);
    }

    private String safeQueryPeriod(String queryPeriod) {
        if (queryPeriod == null || queryPeriod.isBlank()) {
            return "Selected period";
        }
        return queryPeriod;
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "n/a" : value;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}

