package com.bosch.demo.docgrounding.model;

public record IncidentSummaryResponse(
        String summary,
        String question,
        String appName,
        String timeDuration,
        int logCount
) { }
