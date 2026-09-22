package com.bosch.demo.docgrounding.ai.model;

public record JiraTicketPayload(
        String summary,
        String description,
        String issueType,
        String priority
) {}
