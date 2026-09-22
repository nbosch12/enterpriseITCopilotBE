package com.bosch.demo.docgrounding.pipeline;

import com.bosch.demo.docgrounding.ai.AIAnalysisService;
import com.bosch.demo.docgrounding.ai.model.JiraTicketPayload;
import com.bosch.demo.docgrounding.config.JiraProperties;
import com.bosch.demo.docgrounding.jira.JiraClient;
import com.bosch.demo.docgrounding.jira.model.JiraIssueResponse;
import com.bosch.demo.docgrounding.servicenow.ServiceNowClient;
import com.bosch.demo.docgrounding.servicenow.model.ServiceNowTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class TicketHandoffService {

    private static final Logger log = LoggerFactory.getLogger(TicketHandoffService.class);

    private final ServiceNowClient  serviceNowClient;
    private final AIAnalysisService aiAnalysisService;
    private final JiraClient        jiraClient;
    private final JiraProperties    jiraProperties;

    private final Set<String> processedInRun = Collections.synchronizedSet(new HashSet<>());

    public TicketHandoffService(ServiceNowClient serviceNowClient,
                                 AIAnalysisService aiAnalysisService,
                                 JiraClient jiraClient,
                                 JiraProperties jiraProperties) {
        this.serviceNowClient  = serviceNowClient;
        this.aiAnalysisService = aiAnalysisService;
        this.jiraClient        = jiraClient;
        this.jiraProperties    = jiraProperties;
    }

    public List<JiraIssueResponse> processAll() {
        List<ServiceNowTicket>  tickets = serviceNowClient.fetchNewTickets();
        if (tickets.isEmpty()) {
            log.info("No new ServiceNow tickets to process — all tickets have already been synced to Jira.");
            return List.of();
        }
        log.info("{} new ServiceNow ticket(s) fetched for processing.", tickets.size());
        List<JiraIssueResponse> created = new ArrayList<>();

        for (ServiceNowTicket ticket : tickets) {
            if (processedInRun.contains(ticket.id())) continue;
            try {
                log.info("Processing ticket: {} — {}", ticket.id(), ticket.title());
                JiraTicketPayload  payload  = aiAnalysisService.analyze(ticket);
                JiraIssueResponse  response = jiraClient.createIssue(
                        payload, jiraProperties.getProjectKey(), ticket.id());
                processedInRun.add(ticket.id());
                serviceNowClient.writeBackJiraKey(ticket.sysId(), response.key());
                log.info("Created Jira ticket: {} — {}", response.key(), response.browseUrl());
                created.add(response);
            } catch (Exception e) {
                log.error("Failed to process ticket {}: {}", ticket.id(), e.getMessage(), e);
            }
        }
        return created;
    }
}
