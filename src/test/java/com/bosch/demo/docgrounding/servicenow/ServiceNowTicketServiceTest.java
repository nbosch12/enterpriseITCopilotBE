package com.bosch.demo.docgrounding.servicenow;

import com.bosch.demo.docgrounding.ai.model.JiraTicketPayload;
import com.bosch.demo.docgrounding.config.JiraProperties;
import com.bosch.demo.docgrounding.config.ServiceNowProperties;
import com.bosch.demo.docgrounding.jira.JiraClient;
import com.bosch.demo.docgrounding.jira.model.JiraIssueRequest;
import com.bosch.demo.docgrounding.jira.model.JiraIssueResponse;
import com.bosch.demo.docgrounding.servicenow.model.ServiceNowTicket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the deterministic half of the ServiceNow use case: which ticket gets selected, and what
 * is sent to Jira for it.
 */
class ServiceNowTicketServiceTest {

    private RecordingJiraClient jira;
    private MockServiceNowTicketStore store;
    private ServiceNowTicketService service;

    @BeforeEach
    void setUp() {
        JiraProperties jiraProperties = new JiraProperties();
        jiraProperties.setProjectKey("ITSUP");
        jiraProperties.setBaseUrl("https://jira.example.com");

        ServiceNowProperties serviceNowProperties = new ServiceNowProperties();
        serviceNowProperties.setTimezone("Asia/Kolkata");

        jira = new RecordingJiraClient(jiraProperties);
        store = new MockServiceNowTicketStore(new EmptySeedMockClient(jira, jiraProperties));
        service = new ServiceNowTicketService(store, jira, jiraProperties, serviceNowProperties);
    }

    private ServiceNowTicket ticket(String number, String summary, LocalDateTime createdAt,
                                    String issueType, String projectKey) {
        return new ServiceNowTicket(number, null, summary, "Description for " + number,
                "Application", "HIGH", "prem@example.com", createdAt, issueType, projectKey);
    }

    // -------------------------------------------------------------------------
    // Creating and querying mock tickets
    // -------------------------------------------------------------------------

    @Test
    void aPostedTicketKeepsItsNumberSummaryIssueTypeAndProjectKey() {
        ServiceNowTicket saved = service.save(
                ticket("INC1000123", "Payment service 500s", LocalDateTime.now(), "Bug", "ITSUP"));

        assertEquals("INC1000123", saved.id());
        assertEquals("Payment service 500s", saved.title());
        assertEquals("Bug", saved.issueType());
        assertEquals("ITSUP", saved.projectKey());
        assertNotNull(saved.createdAt());
    }

    @Test
    void aTicketNumberIsGeneratedWhenTheCallerOmitsIt() {
        ServiceNowTicket saved = service.save(
                ticket(null, "No number supplied", LocalDateTime.now(), null, null));
        assertNotNull(saved.id());
        assertTrue(saved.id().startsWith("INC"), "generated number should look like a ServiceNow number");
    }

    @Test
    void ticketsCreatedTodayAreReturnedAndOlderOnesAreNot() {
        service.save(ticket("INC1000001", "Today one", LocalDateTime.now(), "Bug", "ITSUP"));
        service.save(ticket("INC1000002", "Today two", LocalDateTime.now().minusHours(1), "Task", "ITSUP"));
        service.save(ticket("INC0009999", "Last week", LocalDateTime.now().minusDays(7), "Bug", "ITSUP"));

        List<String> numbers = service.findCreatedToday().stream().map(ServiceNowTicket::id).toList();

        assertTrue(numbers.contains("INC1000001"));
        assertTrue(numbers.contains("INC1000002"));
        assertFalse(numbers.contains("INC0009999"), "a ticket from last week is not from today");
    }

    @Test
    void noTicketsTodayReturnsAnEmptyListRatherThanFailing() {
        service.save(ticket("INC0009999", "Last week", LocalDateTime.now().minusDays(7), "Bug", "ITSUP"));
        assertTrue(service.findCreatedToday().isEmpty());
    }

    @Test
    void ticketsAreFoundByNumberRegardlessOfCase() {
        service.save(ticket("INC1000123", "Payment service 500s", LocalDateTime.now(), "Bug", "ITSUP"));
        assertTrue(service.findByNumber("inc1000123").isPresent());
        assertTrue(service.findByNumber("  INC1000123  ").isPresent());
        assertTrue(service.findByNumber("INC9999999").isEmpty());
    }

    @Test
    void theConfiguredTimezoneIsUsedAndAnInvalidOneFallsBack() {
        assertEquals("Asia/Kolkata", service.zone().getId());

        ServiceNowProperties broken = new ServiceNowProperties();
        broken.setTimezone("Not/AZone");
        ServiceNowTicketService withBrokenZone = new ServiceNowTicketService(
                store, jira, new JiraProperties(), broken);
        assertEquals("Asia/Kolkata", withBrokenZone.zone().getId(), "an invalid zone must not break lookup");
    }

    // -------------------------------------------------------------------------
    // Jira creation
    // -------------------------------------------------------------------------

    @Test
    void aJiraIssueIsCreatedForTheTicketNamedByNumber() {
        service.save(ticket("INC1000123", "Payment service 500s", LocalDateTime.now(), "Bug", "ITSUP"));
        service.save(ticket("INC1000124", "Printer offline", LocalDateTime.now(), "Task", "ITSUP"));

        ServiceNowTicketService.Outcome outcome = service.createJiraIssue("INC1000123");

        assertTrue(outcome.created());
        assertEquals("INC1000123", outcome.ticket().id(),
                "the ticket must be chosen by number, not by date or project");
        assertEquals("INC1000123", jira.lastSnId, "the SN number must reach the Jira client");
        assertNotNull(outcome.jiraIssue().key());
    }

    @Test
    void theJiraRequestUsesTheTicketsProjectKeyAndIssueType() {
        service.save(ticket("INC1000123", "Payment service 500s", LocalDateTime.now(), "Bug", "PAY"));

        service.createJiraIssue("INC1000123");

        assertEquals("PAY", jira.lastProjectKey, "the ticket's own project key should win");
        assertEquals("Bug", jira.lastPayload.issueType());
        assertEquals("HIGH", jira.lastPayload.priority());
    }

    @Test
    void theConfiguredProjectKeyIsUsedWhenTheTicketNamesNone() {
        service.save(ticket("INC1000125", "No project key", LocalDateTime.now(), null, null));
        service.createJiraIssue("INC1000125");
        assertEquals("ITSUP", jira.lastProjectKey);
        assertNull(jira.lastPayload.issueType(), "an absent issue type is left for Jira to resolve");
    }

    @Test
    void theServiceNowNumberAppearsInTheJiraSummaryAndDescription() {
        service.save(ticket("INC1000123", "Payment service 500s", LocalDateTime.now(), "Bug", "ITSUP"));
        service.createJiraIssue("INC1000123");

        assertTrue(jira.lastPayload.summary().contains("INC1000123"),
                "summary should carry the source number: " + jira.lastPayload.summary());
        assertTrue(jira.lastPayload.description().contains("INC1000123"),
                "description should carry the source number");
    }

    @Test
    void theRequestBodyFollowsTheJiraIssueRequestStructure() {
        service.save(ticket("INC1000123", "Payment service 500s", LocalDateTime.now(), "Bug", "ITSUP"));
        service.createJiraIssue("INC1000123");

        // Build the request the same way JiraClient does, to pin the DTO shape.
        JiraIssueRequest request = new JiraIssueRequest(new JiraIssueRequest.Fields(
                new JiraIssueRequest.Project(jira.lastProjectKey),
                jira.lastPayload.summary(),
                new JiraIssueRequest.IssueType(jira.lastPayload.issueType()),
                new JiraIssueRequest.Priority("Major"),
                jira.lastPayload.description(),
                List.of("SN-" + jira.lastSnId)));

        assertEquals("ITSUP", request.fields().project().key());
        assertEquals("Bug", request.fields().issuetype().name());
        assertTrue(request.fields().labels().contains("SN-INC1000123"));
    }

    // -------------------------------------------------------------------------
    // Refusals, duplicates and failures
    // -------------------------------------------------------------------------

    @Test
    void anUnknownTicketNumberCreatesNothing() {
        ServiceNowTicketService.Outcome outcome = service.createJiraIssue("INC9999999");

        assertEquals(ServiceNowTicketService.Outcome.Status.TICKET_NOT_FOUND, outcome.status());
        assertEquals(0, jira.createCalls, "no Jira call should be made for an unknown ticket");
        assertTrue(outcome.message().contains("INC9999999"));
    }

    @Test
    void aMissingTicketNumberCreatesNothing() {
        ServiceNowTicketService.Outcome outcome = service.createJiraIssue("  ");
        assertEquals(ServiceNowTicketService.Outcome.Status.TICKET_NUMBER_MISSING, outcome.status());
        assertEquals(0, jira.createCalls);
    }

    @Test
    void theSameTicketIsNotSentToJiraTwice() {
        service.save(ticket("INC1000123", "Payment service 500s", LocalDateTime.now(), "Bug", "ITSUP"));

        ServiceNowTicketService.Outcome first = service.createJiraIssue("INC1000123");
        ServiceNowTicketService.Outcome second = service.createJiraIssue("INC1000123");

        assertTrue(first.created());
        assertEquals(ServiceNowTicketService.Outcome.Status.ALREADY_CREATED, second.status());
        assertEquals(1, jira.createCalls, "the second request must not reach Jira");
        assertEquals(first.jiraIssue().key(), second.jiraIssue().key());
    }

    @Test
    void anIssueAlreadyLabelledInJiraIsNotCreatedAgain() {
        service.save(ticket("INC1000200", "Already synced", LocalDateTime.now(), "Bug", "ITSUP"));
        jira.alreadyProcessed = Set.of("INC1000200");

        ServiceNowTicketService.Outcome outcome = service.createJiraIssue("INC1000200");

        assertEquals(ServiceNowTicketService.Outcome.Status.ALREADY_CREATED, outcome.status());
        assertEquals(0, jira.createCalls);
    }

    @Test
    void aJiraFailureLeavesTheMockTicketIntactAndReportsTheProblem() {
        service.save(ticket("INC1000123", "Payment service 500s", LocalDateTime.now(), "Bug", "ITSUP"));
        jira.failure = new RuntimeException("Jira API 401: Bearer abcdef123456 rejected");

        ServiceNowTicketService.Outcome outcome = service.createJiraIssue("INC1000123");

        assertEquals(ServiceNowTicketService.Outcome.Status.JIRA_FAILED, outcome.status());
        assertFalse(outcome.message().contains("abcdef123456"), "the token must not be echoed back");
        assertTrue(service.findByNumber("INC1000123").isPresent(), "the mock ticket must survive");
    }

    @Test
    void aJiraSearchFailureDoesNotBlockCreation() {
        service.save(ticket("INC1000123", "Payment service 500s", LocalDateTime.now(), "Bug", "ITSUP"));
        jira.searchFailure = new RuntimeException("search unavailable");

        ServiceNowTicketService.Outcome outcome = service.createJiraIssue("INC1000123");

        assertTrue(outcome.created(), "a failed duplicate check should not prevent creation");
    }

    // -------------------------------------------------------------------------
    // Stubs
    // -------------------------------------------------------------------------

    /** Records what the service asked Jira to do, and never makes a network call. */
    private static final class RecordingJiraClient extends JiraClient {
        JiraTicketPayload lastPayload;
        String lastProjectKey;
        String lastSnId;
        int createCalls;
        int keySequence = 100;
        Set<String> alreadyProcessed = Set.of();
        RuntimeException failure;
        RuntimeException searchFailure;

        RecordingJiraClient(JiraProperties props) {
            super(null, props, new com.fasterxml.jackson.databind.ObjectMapper());
        }

        @Override
        public Set<String> findAlreadyProcessedIds(String projectKey) {
            if (searchFailure != null) {
                throw searchFailure;
            }
            return alreadyProcessed;
        }

        @Override
        public JiraIssueResponse createIssue(JiraTicketPayload payload, String projectKey, String snId) {
            createCalls++;
            this.lastPayload = payload;
            this.lastProjectKey = projectKey;
            this.lastSnId = snId;
            if (failure != null) {
                throw failure;
            }
            String key = "ITSUP-" + (++keySequence);
            return new JiraIssueResponse(key, String.valueOf(keySequence),
                    "https://jira.example.com/browse/" + key);
        }
    }

    /** A mock client with no seeded tickets, so each test controls the whole store. */
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
