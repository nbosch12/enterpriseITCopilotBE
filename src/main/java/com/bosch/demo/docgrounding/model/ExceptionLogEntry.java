package com.bosch.demo.docgrounding.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Setter
@Getter
@AllArgsConstructor
public class ExceptionLogEntry {
    private LocalDateTime timeGenerated;
    private String appRoleName;
    private int severityLevel;
    private String message;
    private String operationName;
}

