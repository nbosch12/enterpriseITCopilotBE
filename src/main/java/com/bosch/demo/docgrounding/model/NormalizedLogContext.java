package com.bosch.demo.docgrounding.model;

import java.util.List;

public record NormalizedLogContext(
        String queryPeriod,
        int totalCount,
        List<String> impactedApps,
        List<String> topPatterns,
        List<String> recentEvents,
        String normalizedText
) { }

