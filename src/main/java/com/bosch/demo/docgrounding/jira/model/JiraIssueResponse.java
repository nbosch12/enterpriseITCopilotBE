package com.bosch.demo.docgrounding.jira.model;

public record JiraIssueResponse(
        String key,
        String id,
        String browseUrl
) {}
