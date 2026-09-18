package com.bosch.demo.docgrounding.model;

import jakarta.validation.constraints.NotBlank;

public record IncidentSummaryRequest(
        @NotBlank String question,
        String appName,
        String timeDuration,
        Integer limit
) { }
