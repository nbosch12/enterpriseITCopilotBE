package com.bosch.demo.docgrounding.jira.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JiraIssueRequest(Fields fields) {

    public record Fields(
            Project      project,
            String       summary,
            IssueType    issuetype,
            Priority     priority,
            String       description,
            List<String> labels
    ) {}

    public record Project(String key) {}
    public record IssueType(String name) {}
    public record Priority(String name) {}
}
