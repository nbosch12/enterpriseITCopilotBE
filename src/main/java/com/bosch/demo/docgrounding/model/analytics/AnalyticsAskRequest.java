package com.bosch.demo.docgrounding.model.analytics;

import jakarta.validation.constraints.NotBlank;

/** Natural-language analytics question for {@code POST /api/mongodb/analytics/ask}. */
public record AnalyticsAskRequest(@NotBlank String question) {
}
