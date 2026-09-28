package com.bosch.demo.docgrounding.service.tools;

import com.bosch.demo.docgrounding.ai.model.JiraTicketPayload;
import com.bosch.demo.docgrounding.config.JiraProperties;
import com.bosch.demo.docgrounding.config.ServiceNowProperties;
import com.bosch.demo.docgrounding.jira.JiraClient;
import com.bosch.demo.docgrounding.jira.model.JiraIssueResponse;
import com.bosch.demo.docgrounding.model.ToolRequest;
import com.bosch.demo.docgrounding.model.ToolResult;
import com.bosch.demo.docgrounding.servicenow.MockServiceNowClient;
import com.bosch.demo.docgrounding.servicenow.MockServiceNowTicketStore;
import com.bosch.demo.docgrounding.servicenow.ServiceNowTicketService;
import com.bosch.demo.docgrounding.servicenow.model.ServiceNowTicket;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers how the copilot tool interprets a question: what it lists, when it refuses to create, and
 * what it hands the model as context.
 */
class ServiceNowToolTest {

    private RecordingJiraClient jira;
    private ServiceNowTicketService service;
    private ServiceNowTool tool;

    @BeforeEach
    void setUp() {
        JiraProperties jiraProperties = new JiraProperties();
        jiraProperties.setProjectKey("ITSUP");
        jiraProperties.setBaseUrl("https://jira.example.com");

        ServiceNowProperties serviceNowProperties = new ServiceNowProperties();
        serviceNowProperties.setTimezone("Asia/Kolkata");

        jira = new RecordingJiraClient(jiraProperties);
        MockServiceNowTicketStore store =
                new MockServiceNowTicketStore(new EmptySeedMockClient(jira, jiraProperties));
        service = new ServiceNowTicketService(store, jira, jiraProperties, serviceNowProperties);
        tool = new ServiceNowTool(service, new ObjectMapper());
    }

    private void seedToday(String number, String summary, String issueType, String projectKey) {
        service.save(new ServiceNowTicket(number, null, summary, "Description for " + number,
                "Application", "HIGH", "prem@example.com", LocalDateTime.now(), issueType, projectKey));
    }

    private ToolResult ask(String question) {
        return tool.execute(new ToolRequest(question, "session-1", Map.of())).block();
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    @Test
    void theTicketNumberIsRecognisedInAQuestion() {
        assertEquals("INC1000123",
                tool.extractTicketNumber("Create a Jira ticket for ServiceNow ticket INC1000123 created today")
                        .orElseThrow());
        assertEquals("INC0010001", tool.extractTicketNumber("tell me about inc0010001").orElseThrow());
        assertTrue(tool.extractTicketNumber("create a jira ticket for today's incident").isEmpty());
    }

    @Test
    void todayIsRecognisedInAQuestion() {
        assertTrue(tool.mentionsToday("which ServiceNow tickets were created today"));
        assertFalse(tool.mentionsToday("list all ServiceNow tickets"));
    }

    // -------------------------------------------------------------------------
    // Querying
    // -------------------------------------------------------------------------

    @Test
    void todaysTicketsAreListedWithNumberSummaryIssueTypeAndProjectKey() {
        seedToday("INC1000123", "Payment service 500s", "Bug", "PAY");

        ToolResult result = ask("Show me the ServiceNow tickets created today");

        assertTrue(result.success());
        assertTrue(result.summary().contains("INC1000123"));
        assertTrue(result.summary().contains("Payment service 500s"));
        assertTrue(result.summary().contains("Bug"));
        assertTrue(result.summary().contains("PAY"));
        assertEquals("CREATED_TODAY", result.metadata().get("lookup"));
        assertEquals(Boolean.TRUE, result.metadata().get("strictGrounding"));
    }

    @Test
    void noTicketsTodayIsStatedPlainly() {
        ToolResult result = ask("Which ServiceNow tickets were created today?");

        assertTrue(result.success(), "an empty result is not a failure");
        assertEquals(0, result.metadata().get("count"));
        assertTrue(result.summary().contains("No mock ServiceNow tickets were created today"));
        assertTrue(result.summary().contains("Do not invent"),
                "the model must be told not to fill the gap");
    }

    @Test
    void severalTicketsAreListedSoTheUserCanChoose() {
        seedToday("INC1000123", "Payment service 500s", "Bug", "PAY");
        seedToday("INC1000124", "Printer offline", "Task", "ITSUP");

        ToolResult result = ask("Show ServiceNow tickets created today");

        assertEquals(2, result.metadata().get("count"));
        assertTrue(result.summary().contains("INC1000123"));
        assertTrue(result.summary().contains("INC1000124"));
        assertTrue(result.summary().contains("must name the ServiceNow ticket number"));
    }

    @Test
    void askingAboutOneTicketByNumberReturnsOnlyThatTicket() {
        seedToday("INC1000123", "Payment service 500s", "Bug", "PAY");
        seedToday("INC1000124", "Printer offline", "Task", "ITSUP");

        ToolResult result = ask("What is ServiceNow ticket INC1000124 about?");

        assertTrue(result.summary().contains("INC1000124"));
        assertFalse(result.summary().contains("INC1000123"));
    }

    @Test
    void anUnknownNumberIsReportedWithoutInventingDetails() {
        ToolResult result = ask("Tell me about ServiceNow ticket INC9999999");
        assertEquals(Boolean.FALSE, result.metadata().get("found"));
        assertTrue(result.summary().contains("do not invent its details"));
    }

    // -------------------------------------------------------------------------
    // Creating
    // -------------------------------------------------------------------------

    @Test
    void aJiraIssueIsCreatedWhenTheQuestionNamesTheTicket() {
        seedToday("INC1000123", "Payment service 500s", "Bug", "PAY");
        seedToday("INC1000124", "Printer offline", "Task", "ITSUP");

        ToolResult result = ask("Create a Jira ticket for ServiceNow ticket INC1000123 created today");

        assertEquals("CREATE_JIRA", result.metadata().get("action"));
        assertEquals("CREATED", result.metadata().get("outcome"));
        assertEquals("INC1000123", result.metadata().get("serviceNowTicketNumber"));
        assertEquals("PAY", result.metadata().get("projectKey"));
        assertEquals("INC1000123", jira.lastSnId, "the named ticket must be the one sent to Jira");
        assertTrue(result.summary().contains(String.valueOf(result.metadata().get("jiraKey"))));
    }

    @Test
    void creationIsRefusedWhenNoTicketNumberIsNamedAndSeveralMatch() {
        seedToday("INC1000123", "Payment service 500s", "Bug", "PAY");
        seedToday("INC1000124", "Printer offline", "Task", "ITSUP");

        ToolResult result = ask("Create a Jira ticket for the ServiceNow ticket created today");

        assertEquals("CREATE_REFUSED", result.metadata().get("action"));
        assertEquals("TICKET_NUMBER_REQUIRED", result.metadata().get("reason"));
        assertEquals(0, jira.createCalls, "nothing may be created while the choice is ambiguous");
        assertTrue(result.summary().contains("INC1000123"));
        assertTrue(result.summary().contains("INC1000124"));
        assertTrue(result.summary().contains("do not choose by project key or creation date alone"));
    }

    @Test
    void creationIsRefusedForAnUnknownTicketNumber() {
        seedToday("INC1000123", "Payment service 500s", "Bug", "PAY");

        ToolResult result = ask("Create a Jira ticket for ServiceNow ticket INC9999999");

        assertEquals("TICKET_NOT_FOUND", result.metadata().get("outcome"));
        assertEquals(0, jira.createCalls);
    }

    @Test
    void aSecondRequestForTheSameTicketDoesNotCreateAnotherIssue() {
        seedToday("INC1000123", "Payment service 500s", "Bug", "PAY");

        ask("Create a Jira ticket for ServiceNow ticket INC1000123");
        ToolResult second = ask("Create a Jira ticket for ServiceNow ticket INC1000123");

        assertEquals("ALREADY_CREATED", second.metadata().get("outcome"));
        assertEquals(1, jira.createCalls);
    }

    @Test
    void aJiraFailureIsReportedWithoutLeakingTheToken() {
        seedToday("INC1000123", "Payment service 500s", "Bug", "PAY");
        jira.failure = new RuntimeException("Jira API 401: Bearer supersecrettoken rejected");

        ToolResult result = ask("Create a Jira ticket for ServiceNow ticket INC1000123");

        assertEquals("JIRA_FAILED", result.metadata().get("outcome"));
        assertFalse(result.summary().contains("supersecrettoken"));
        assertTrue(result.summary().contains("can be retried"));
    }

    @Test
    void aQuestionAboutTicketsNeverCreatesAnything() {
        seedToday("INC1000123", "Payment service 500s", "Bug", "PAY");

        ask("Which ServiceNow tickets were created today?");
        ask("What is ServiceNow ticket INC1000123 about?");

        assertEquals(0, jira.createCalls, "a read-only question must not raise a Jira issue");
    }

    // -------------------------------------------------------------------------
    // Stubs
    // -------------------------------------------------------------------------

    private static final class RecordingJiraClient extends JiraClient {
        String lastSnId;
        int createCalls;
        int keySequence = 100;
        RuntimeException failure;

        RecordingJiraClient(JiraProperties props) {
            super(null, props, new ObjectMapper());
        }

        @Override
        public Set<String> findAlreadyProcessedIds(String projectKey) {
            return Set.of();
        }

        @Override
        public JiraIssueResponse createIssue(JiraTicketPayload payload, String projectKey, String snId) {
            createCalls++;
            this.lastSnId = snId;
            if (failure != null) {
                throw failure;
            }
            String key = "ITSUP-" + (++keySequence);
            return new JiraIssueResponse(key, String.valueOf(keySequence),
                    "https://jira.example.com/browse/" + key);
        }
    }

    private static final class EmptySeedMockClient extends MockServiceNowClient {
        EmptySeedMockClient(JiraClient jiraClient, JiraProperties jiraProperties) {
            super(jiraClient, jiraProperties);
        }

        @Override
        public List<ServiceNowTicket> allSeededTickets() {
            return new ArrayList<>();
        }
    }
}
