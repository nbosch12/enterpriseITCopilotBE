package com.bosch.demo.docgrounding.servicenow;

import com.bosch.demo.docgrounding.ai.model.JiraTicketPayload;
import com.bosch.demo.docgrounding.config.JiraProperties;
import com.bosch.demo.docgrounding.config.ServiceNowProperties;
import com.bosch.demo.docgrounding.jira.JiraClient;
import com.bosch.demo.docgrounding.jira.model.JiraIssueResponse;
import com.bosch.demo.docgrounding.servicenow.model.ServiceNowTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic lookup and Jira hand-off for mock ServiceNow tickets.
 *
 * <p>Everything that decides <em>which</em> ticket is acted on happens here, in plain Java: the
 * ticket is found by its number, its existence is verified, and only then is a Jira issue raised.
 * The language model phrases the answer; it never picks the ticket.</p>
 *
 * <p>Jira creation reuses {@link JiraClient#createIssue}, so the request body, the
 * {@code SN-<number>} traceability label and the error handling are the ones the existing pipeline
 * already uses.</p>
 */
@Service
public class ServiceNowTicketService {

    private static final Logger log = LoggerFactory.getLogger(ServiceNowTicketService.class);

    private final MockServiceNowTicketStore store;
    private final JiraClient jiraClient;
    private final JiraProperties jiraProperties;
    private final ServiceNowProperties serviceNowProperties;

    /**
     * ServiceNow ticket number to the Jira issue already raised for it, for this instance.
     *
     * <p>Jira itself is the durable record: {@link JiraClient#findAlreadyProcessedIds} reads the
     * {@code SN-} labels back. This map is the cheap first line of defence so a repeated request
     * does not depend on a Jira round trip, and so duplicate protection still holds when Jira
     * search is unavailable.</p>
     */
    private final Map<String, JiraIssueResponse> createdByTicketNumber = new ConcurrentHashMap<>();

    public ServiceNowTicketService(MockServiceNowTicketStore store,
                                   JiraClient jiraClient,
                                   JiraProperties jiraProperties,
                                   ServiceNowProperties serviceNowProperties) {
        this.store = store;
        this.jiraClient = jiraClient;
        this.jiraProperties = jiraProperties;
        this.serviceNowProperties = serviceNowProperties;
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    /** The configured zone, falling back to Asia/Kolkata when the value is missing or invalid. */
    public ZoneId zone() {
        String configured = serviceNowProperties.getTimezone();
        if (configured == null || configured.isBlank()) {
            return ZoneId.of("Asia/Kolkata");
        }
        try {
            return ZoneId.of(configured.trim());
        } catch (RuntimeException ex) {
            log.warn("servicenow.timezone '{}' is not a valid zone; using Asia/Kolkata.", configured);
            return ZoneId.of("Asia/Kolkata");
        }
    }

    /** Today's date in the configured zone. */
    public LocalDate today() {
        return LocalDate.now(zone());
    }

    /** Mock tickets created today, newest first. */
    public List<ServiceNowTicket> findCreatedToday() {
        return store.findCreatedOn(today(), zone());
    }

    /** Mock tickets created on a specific day, newest first. */
    public List<ServiceNowTicket> findCreatedOn(LocalDate day) {
        return store.findCreatedOn(day, zone());
    }

    /** One ticket by its ServiceNow number. */
    public Optional<ServiceNowTicket> findByNumber(String ticketNumber) {
        return store.findByNumber(ticketNumber);
    }

    /** Every known mock ticket, posted and seeded. */
    public List<ServiceNowTicket> findAll() {
        return store.findAll();
    }

    /** Store a mock ticket. */
    public ServiceNowTicket save(ServiceNowTicket ticket) {
        return store.save(ticket);
    }

    // -------------------------------------------------------------------------
    // Jira hand-off
    // -------------------------------------------------------------------------

    /**
     * Raise a Jira issue for one named ServiceNow ticket.
     *
     * <p>The ticket must exist. Nothing is created for a number the mock does not know, and nothing
     * is created twice for the same number.</p>
     *
     * @param ticketNumber the ServiceNow ticket number, e.g. {@code INC1000123}
     * @return the outcome: created, already existing, ticket unknown, or Jira failure
     */
    public Outcome createJiraIssue(String ticketNumber) {
        String wanted = ticketNumber == null ? "" : ticketNumber.trim();
        if (wanted.isEmpty()) {
            return Outcome.ticketNumberMissing();
        }

        Optional<ServiceNowTicket> found = store.findByNumber(wanted);
        if (found.isEmpty()) {
            log.info("Jira hand-off refused: no mock ServiceNow ticket named {}.", wanted);
            return Outcome.unknownTicket(wanted);
        }
        ServiceNowTicket ticket = found.get();

        JiraIssueResponse alreadyCreated = createdByTicketNumber.get(ticket.id().toUpperCase());
        if (alreadyCreated != null) {
            log.info("Jira hand-off skipped: {} already has Jira issue {}.", ticket.id(), alreadyCreated.key());
            return Outcome.duplicate(ticket, alreadyCreated);
        }

        String projectKey = resolveProjectKey(ticket);
        try {
            // Jira is the durable record of what has been processed; consult it before creating.
            if (jiraClient.findAlreadyProcessedIds(projectKey).contains(ticket.id())) {
                log.info("Jira hand-off skipped: {} is already labelled on an issue in {}.",
                        ticket.id(), projectKey);
                return Outcome.duplicateInJira(ticket);
            }
        } catch (RuntimeException ex) {
            // A search failure must not block creation; the in-memory map still guards this run.
            log.warn("Could not check Jira for existing issues for {}: {}", ticket.id(), ex.getMessage());
        }

        JiraTicketPayload payload = toPayload(ticket);
        try {
            JiraIssueResponse response = jiraClient.createIssue(payload, projectKey, ticket.id());
            createdByTicketNumber.put(ticket.id().toUpperCase(), response);
            log.info("Created Jira issue {} from ServiceNow ticket {}.", response.key(), ticket.id());
            return Outcome.created(ticket, response);
        } catch (RuntimeException ex) {
            // The mock ticket survives a Jira failure; only the hand-off failed.
            log.error("Jira creation failed for ServiceNow ticket {}: {}", ticket.id(), ex.getMessage());
            return Outcome.jiraFailed(ticket, summarise(ex));
        }
    }

    /**
     * Build the Jira payload from the ticket's own fields.
     *
     * <p>The ServiceNow number goes into both the summary and the description, so the origin is
     * readable on the issue itself and not only in the {@code SN-} label.</p>
     */
    JiraTicketPayload toPayload(ServiceNowTicket ticket) {
        String summary = "[" + ticket.id() + "] " + valueOr(ticket.title(), "ServiceNow ticket " + ticket.id());

        StringBuilder description = new StringBuilder();
        description.append("Raised from ServiceNow ticket ").append(ticket.id()).append(".\n\n");
        if (notBlank(ticket.description())) {
            description.append(ticket.description()).append("\n\n");
        }
        description.append("ServiceNow ticket number: ").append(ticket.id()).append('\n');
        if (notBlank(ticket.category())) {
            description.append("Category: ").append(ticket.category()).append('\n');
        }
        if (notBlank(ticket.requestedBy())) {
            description.append("Requested by: ").append(ticket.requestedBy()).append('\n');
        }
        if (ticket.createdAt() != null) {
            description.append("Created at: ").append(ticket.createdAt()).append('\n');
        }

        // issueType may be null; JiraClient resolves it against the project's real issue types.
        return new JiraTicketPayload(
                summary,
                description.toString().trim(),
                ticket.issueType(),
                ticket.priority());
    }

    /** The ticket's own project key when it names one, otherwise the configured default. */
    public String resolveProjectKey(ServiceNowTicket ticket) {
        if (notBlank(ticket.projectKey())) {
            return ticket.projectKey().trim();
        }
        return jiraProperties.getProjectKey();
    }

    /** Jira issues raised in this instance, keyed by ServiceNow ticket number. */
    public Map<String, JiraIssueResponse> createdIssues() {
        return Map.copyOf(createdByTicketNumber);
    }

    /** Clears the in-memory duplicate guard. Intended for tests. */
    public void clearCreated() {
        createdByTicketNumber.clear();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Keeps Jira error detail useful without leaking a token, URL credential or stack trace. */
    private String summarise(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        String cleaned = message
                .replaceAll("(?i)(bearer|authorization)\\s+\\S+", "$1 ***")
                .replaceAll("(?i)([a-z][a-z0-9+.\\-]*://)[^/\\s:@]+:[^/\\s@]+@", "$1***:***@");
        return cleaned.length() <= 300 ? cleaned : cleaned.substring(0, 300) + "...";
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String valueOr(String value, String fallback) {
        return notBlank(value) ? value : fallback;
    }

    /** What happened when a Jira hand-off was attempted. */
    public record Outcome(
            Status status,
            ServiceNowTicket ticket,
            JiraIssueResponse jiraIssue,
            String message) {

        public enum Status {
            /** A new Jira issue was raised. */
            CREATED,
            /** This ticket already has a Jira issue; nothing was created. */
            ALREADY_CREATED,
            /** No mock ServiceNow ticket carries that number. */
            TICKET_NOT_FOUND,
            /** No ticket number was supplied. */
            TICKET_NUMBER_MISSING,
            /** Jira rejected the request or was unreachable. */
            JIRA_FAILED
        }

        public boolean created() {
            return status == Status.CREATED;
        }

        static Outcome created(ServiceNowTicket ticket, JiraIssueResponse issue) {
            return new Outcome(Status.CREATED, ticket, issue,
                    "Created Jira issue " + issue.key() + " from ServiceNow ticket " + ticket.id() + ".");
        }

        static Outcome duplicate(ServiceNowTicket ticket, JiraIssueResponse issue) {
            return new Outcome(Status.ALREADY_CREATED, ticket, issue,
                    "ServiceNow ticket " + ticket.id() + " already has Jira issue " + issue.key()
                            + ". No new issue was created.");
        }

        static Outcome duplicateInJira(ServiceNowTicket ticket) {
            return new Outcome(Status.ALREADY_CREATED, ticket, null,
                    "A Jira issue labelled SN-" + ticket.id() + " already exists. No new issue was created.");
        }

        static Outcome unknownTicket(String ticketNumber) {
            return new Outcome(Status.TICKET_NOT_FOUND, null, null,
                    "No mock ServiceNow ticket named " + ticketNumber + " exists, so no Jira issue was created.");
        }

        static Outcome ticketNumberMissing() {
            return new Outcome(Status.TICKET_NUMBER_MISSING, null, null,
                    "A ServiceNow ticket number is required to create a Jira issue.");
        }

        static Outcome jiraFailed(ServiceNowTicket ticket, String detail) {
            return new Outcome(Status.JIRA_FAILED, ticket, null,
                    "Jira issue creation failed for ServiceNow ticket " + ticket.id() + ": " + detail
                            + " The ServiceNow ticket is unchanged and can be retried.");
        }
    }
}
