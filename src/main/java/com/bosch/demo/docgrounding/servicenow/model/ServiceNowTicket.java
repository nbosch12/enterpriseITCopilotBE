package com.bosch.demo.docgrounding.servicenow.model;

import java.time.LocalDateTime;

/**
 * A ServiceNow ticket as the mock and the real client both represent it.
 *
 * @param id          the ServiceNow ticket number, e.g. {@code INC0010001}
 * @param sysId       ServiceNow internal sys_id
 * @param title       short summary, used as the Jira summary
 * @param description long description, used as the Jira description
 * @param category    ServiceNow category (Network, Hardware, ...)
 * @param priority    ServiceNow priority (CRITICAL, HIGH, MEDIUM, LOW)
 * @param requestedBy requester's email
 * @param createdAt   creation timestamp, used to select tickets created today
 * @param issueType   Jira issue type to raise for this ticket, e.g. {@code Bug}. Optional: when
 *                    absent the Jira integration falls back to its configured default.
 * @param projectKey  Jira project key to raise the issue in. Optional: when absent
 *                    {@code jira.project-key} is used.
 */
public record ServiceNowTicket(
        String        id,
        String        sysId,
        String        title,
        String        description,
        String        category,
        String        priority,
        String        requestedBy,
        LocalDateTime createdAt,
        String        issueType,
        String        projectKey
) {
    /**
     * Kept so existing callers that predate the issue type and project key still compile and
     * behave exactly as before; both fields default to absent.
     */
    public ServiceNowTicket(
            String id,
            String sysId,
            String title,
            String description,
            String category,
            String priority,
            String requestedBy,
            LocalDateTime createdAt) {
        this(id, sysId, title, description, category, priority, requestedBy, createdAt, null, null);
    }
}
