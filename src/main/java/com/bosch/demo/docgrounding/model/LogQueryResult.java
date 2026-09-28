package com.bosch.demo.docgrounding.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class LogQueryResult {
    private int totalCount;
    private Map<String, Long> countByApp;
    private List<ExceptionLogEntry> latestExceptions;
    private String queryPeriod;
    private boolean hasErrors;
    private String errorMessage;
}

