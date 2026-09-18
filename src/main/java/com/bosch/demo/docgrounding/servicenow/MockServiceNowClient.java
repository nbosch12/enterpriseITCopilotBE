package com.bosch.demo.docgrounding.servicenow;

import com.bosch.demo.docgrounding.config.JiraProperties;
import com.bosch.demo.docgrounding.jira.JiraClient;
import com.bosch.demo.docgrounding.servicenow.model.ServiceNowTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Primary
public class MockServiceNowClient implements ServiceNowClient {

    private static final Logger log = LoggerFactory.getLogger(MockServiceNowClient.class);

    private final JiraClient     jiraClient;
    private final JiraProperties jiraProperties;

    public MockServiceNowClient(JiraClient jiraClient, JiraProperties jiraProperties) {
        this.jiraClient     = jiraClient;
        this.jiraProperties = jiraProperties;
    }

    private static final List<ServiceNowTicket> ALL_TICKETS = List.of(
        new ServiceNowTicket("INC0010001", "mock-sys-id-001",
            "Cannot access VPN from home office",
            "Employee reports complete inability to connect to corporate VPN since firmware update on router. "
            + "Affects all VPN clients. Remote work is fully blocked.",
            "Network", "HIGH", "john.doe@example.com", LocalDateTime.now().minusMinutes(15)),
        new ServiceNowTicket("INC0010002", "mock-sys-id-002",
            "Password expired — locked out of Active Directory",
            "User account locked after multiple failed login attempts following password expiry. "
            + "User cannot access email, shared drives, or any SSO-connected applications.",
            "Identity", "LOW", "jane.smith@example.com", LocalDateTime.now().minusMinutes(45)),
        new ServiceNowTicket("INC0010003", "mock-sys-id-003",
            "Laptop SSD failure — production data at risk",
            "SSD on primary development laptop reporting imminent failure via SMART diagnostics. "
            + "System throwing I/O errors during builds. Requires immediate hardware replacement.",
            "Hardware", "CRITICAL", "bob.jones@example.com", LocalDateTime.now().minusMinutes(5)),
        new ServiceNowTicket("INC0010004", "mock-sys-id-004",
            "Install Adobe Acrobat Pro on engineering workstation",
            "Engineering team lead requests Adobe Acrobat Pro for PDF review. "
            + "License pre-purchased under enterprise agreement EA-2024-0892.",
            "Software", "MEDIUM", "alice.martin@example.com", LocalDateTime.now().minusHours(2)),
        new ServiceNowTicket("INC0010005", "mock-sys-id-005",
            "Outlook not syncing — emails delayed by 4+ hours",
            "Finance department (6 users) reporting significant email delivery delays since this morning. "
            + "Impacting invoice approvals.",
            "Email", "HIGH", "carol.white@example.com", LocalDateTime.now().minusMinutes(30))
    );

    @Override
    public List<ServiceNowTicket> fetchNewTickets() {
        Set<String> alreadyProcessed = jiraClient.findAlreadyProcessedIds(jiraProperties.getProjectKey());
        List<ServiceNowTicket> unprocessed = ALL_TICKETS.stream()
                .filter(t -> !alreadyProcessed.contains(t.id()))
                .collect(Collectors.toList());
        log.info("Mock ServiceNow: {}/{} ticket(s) unprocessed.", unprocessed.size(), ALL_TICKETS.size());
        return unprocessed;
    }

    @Override
    public void writeBackJiraKey(String sysId, String jiraKey) {
        log.debug("Mock ServiceNow write-back: no-op for sysId={}, jiraKey={}.", sysId, jiraKey);
    }
}
