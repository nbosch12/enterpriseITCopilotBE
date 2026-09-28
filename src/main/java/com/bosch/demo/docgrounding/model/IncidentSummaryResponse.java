package com.bosch.demo.docgrounding.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class IncidentSummaryResponse {
    private String question;
    private String title;
    private String overallSeverity;
    private String summary;
    private List<String> impactedApps;
    private List<String> likelyCauses;
    private List<String> recommendedActions;
    private List<String> keyObservations;
    private boolean usedFallback;
    private String rawModelResponse;
    private LogQueryResult sourceLogs;
}