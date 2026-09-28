package com.bosch.demo.docgrounding.service.tools;

import com.bosch.demo.docgrounding.model.ToolRequest;
import com.bosch.demo.docgrounding.model.ToolResult;
import com.bosch.demo.docgrounding.servicenow.ServiceNowTicketService;
import com.bosch.demo.docgrounding.servicenow.model.ServiceNowTicket;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers questions about mock ServiceNow tickets and raises Jira issues from them.
 *
 * <p>Like the other retrieval tools, this one only gathers facts: it looks tickets up, decides
 * whether a request is unambiguous, and hands the result back as context for the model to phrase.
 * The selection of a ticket is made here in Java, keyed on the ServiceNow ticket number, never by
 * the model.</p>
 *
 * <p>Creating a Jira issue is a side effect, so the bar is deliberately high: the question must
 * both ask for creation and name a ticket number. A request that is merely about "the ticket from
 * today" returns the candidates and asks which one, leaving Jira untouched.</p>
 */
@Component
public class ServiceNowTool implements CopilotTool {

    private static final Logger log = LoggerFactory.getLogger(ServiceNowTool.class);

    private static final String TOOL_NAME = "SERVICENOW_JIRA";

    /** Same marker the Cosmos tool uses, so the orchestrator applies strict grounding rules. */
    private static final String STRICT_GROUNDING_FLAG = "strictGrounding";

    /** ServiceNow numbers: INC0010001, CHG12345, RITM0004321, and so on. */
    private static final Pattern TICKET_NUMBER =
            Pattern.compile("\\b((?:INC|CHG|REQ|RITM|PRB|TASK)[0-9]{3,})\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern CREATE_INTENT = Pattern.compile(
            "\\b(create|raise|open|log|file|make)\\b.{0,40}\\b(jira|issue|ticket|bug|story)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final ServiceNowTicketService ticketService;
    private final ObjectMapper objectMapper;

    public ServiceNowTool(ServiceNowTicketService ticketService, ObjectMapper objectMapper) {
        this.ticketService = ticketService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return "Looks up mock ServiceNow tickets (including those created today) and creates a Jira "
                + "issue from a ServiceNow ticket identified by its ticket number.";
    }

    /**
     * "ticket" and "tickets" are listed alongside the ServiceNow and Jira nouns so that a plain
     * question such as "which ServiceNow tickets were created today" outscores the logs tool, which
     * also matches the word "today". Ticket wording belongs to this tool; no other tool claims it.
     */
    @Override
    public String[] keywords() {
        return new String[]{
                "servicenow", "service now", "snow ticket", "incident ticket",
                "ticket", "tickets", "inc00", "inc1", "ritm", "chg0", "prb0",
                "jira", "jira ticket", "jira issue", "raise a ticket", "create a ticket"
        };
    }

    @Override
    public Mono<ToolResult> execute(ToolRequest request) {
        // Jira and the ticket store are blocking; keep them off the event loop.
        return Mono.fromCallable(() -> handle(request))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ex -> {
                    log.error("[{}] ServiceNow tool failed: {}", TOOL_NAME, ex.toString());
                    return Mono.just(ToolResult.error(TOOL_NAME,
                            "The ServiceNow ticket lookup failed (" + ex.getClass().getSimpleName() + ")."));
                });
    }

    // -------------------------------------------------------------------------
    // Intent handling
    // -------------------------------------------------------------------------

    private ToolResult handle(ToolRequest request) {
        String question = request.question() == null ? "" : request.question();
        Optional<String> ticketNumber = extractTicketNumber(question);
        boolean wantsCreate = CREATE_INTENT.matcher(question).find();

        if (wantsCreate) {
            return handleCreate(question, ticketNumber);
        }
        return handleQuery(question, ticketNumber);
    }

    /**
     * Listing tickets. A named ticket narrows the answer to that one.
     */
    private ToolResult handleQuery(String question, Optional<String> ticketNumber) {
        if (ticketNumber.isPresent()) {
            Optional<ServiceNowTicket> found = ticketService.findByNumber(ticketNumber.get());
            if (found.isEmpty()) {
                return context("No mock ServiceNow ticket named " + ticketNumber.get() + " exists.\n"
                                + "Tell the user the ticket was not found and do not invent its details.",
                        Map.of("lookup", "BY_NUMBER", "ticketNumber", ticketNumber.get(), "found", false),
                        "[]");
            }
            ServiceNowTicket ticket = found.get();
            return context("One mock ServiceNow ticket matched.\n\n" + render(ticket),
                    Map.of("lookup", "BY_NUMBER", "ticketNumber", ticket.id(), "found", true),
                    json(List.of(ticket)));
        }

        boolean today = mentionsToday(question);
        List<ServiceNowTicket> tickets = today ? ticketService.findCreatedToday() : ticketService.findAll();
        LocalDate day = ticketService.today();

        if (tickets.isEmpty()) {
            String message = today
                    ? "No mock ServiceNow tickets were created today (" + day + ", "
                      + ticketService.zone().getId() + ").\n"
                      + "Tell the user plainly that none were created today. Do not invent tickets."
                    : "The mock ServiceNow store holds no tickets at all.";
            return context(message,
                    Map.of("lookup", today ? "CREATED_TODAY" : "ALL", "count", 0,
                           "date", day.toString(), "timezone", ticketService.zone().getId()),
                    "[]");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(today
                ? "Mock ServiceNow tickets created today (" + day + ", " + ticketService.zone().getId() + "):"
                : "All known mock ServiceNow tickets:");
        sb.append("\nTickets found: ").append(tickets.size()).append("\n\n");
        for (ServiceNowTicket ticket : tickets) {
            sb.append(render(ticket)).append('\n');
        }
        if (tickets.size() > 1) {
            sb.append("\nThere is more than one ticket. If the user wants a Jira issue created, they must "
                    + "name the ServiceNow ticket number. List the numbers and summaries and ask which one.");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("lookup", today ? "CREATED_TODAY" : "ALL");
        metadata.put("count", tickets.size());
        metadata.put("date", day.toString());
        metadata.put("timezone", ticketService.zone().getId());
        metadata.put("ticketNumbers", tickets.stream().map(ServiceNowTicket::id).toList());
        return context(sb.toString(), metadata, json(tickets));
    }

    /**
     * Creating a Jira issue. Refuses to act without an unambiguous ticket number.
     */
    private ToolResult handleCreate(String question, Optional<String> ticketNumber) {
        if (ticketNumber.isEmpty()) {
            List<ServiceNowTicket> candidates = mentionsToday(question)
                    ? ticketService.findCreatedToday()
                    : ticketService.findAll();

            if (candidates.isEmpty()) {
                return context("No Jira issue was created: the mock ServiceNow store has no matching tickets.\n"
                                + "Tell the user there is nothing to create an issue from.",
                        Map.of("action", "CREATE_REFUSED", "reason", "NO_CANDIDATES", "count", 0),
                        "[]");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("No Jira issue was created, because the request did not name a ServiceNow ticket "
                    + "number and the match is ambiguous.\n")
              .append("Candidate tickets:\n\n");
            for (ServiceNowTicket ticket : candidates) {
                sb.append(render(ticket)).append('\n');
            }
            sb.append("\nAsk the user which ServiceNow ticket number to use. Do not choose one for them, "
                    + "and do not choose by project key or creation date alone.");

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("action", "CREATE_REFUSED");
            metadata.put("reason", "TICKET_NUMBER_REQUIRED");
            metadata.put("count", candidates.size());
            metadata.put("ticketNumbers", candidates.stream().map(ServiceNowTicket::id).toList());
            return context(sb.toString(), metadata, json(candidates));
        }

        ServiceNowTicketService.Outcome outcome = ticketService.createJiraIssue(ticketNumber.get());

        StringBuilder sb = new StringBuilder();
        sb.append(outcome.message()).append("\n\n");
        if (outcome.ticket() != null) {
            sb.append("Source ServiceNow ticket:\n").append(render(outcome.ticket())).append('\n');
        }
        if (outcome.jiraIssue() != null) {
            sb.append("Created Jira issue:\n")
              .append("  key: ").append(outcome.jiraIssue().key()).append('\n');
            if (outcome.jiraIssue().browseUrl() != null) {
                sb.append("  url: ").append(outcome.jiraIssue().browseUrl()).append('\n');
            }
        }
        sb.append("\nReport exactly this outcome. Do not claim an issue was created unless a Jira key "
                + "appears above.");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("action", "CREATE_JIRA");
        metadata.put("outcome", outcome.status().name());
        metadata.put("ticketNumber", ticketNumber.get());
        if (outcome.ticket() != null) {
            metadata.put("serviceNowTicketNumber", outcome.ticket().id());
            metadata.put("projectKey", ticketService.resolveProjectKey(outcome.ticket()));
        }
        if (outcome.jiraIssue() != null) {
            metadata.put("jiraKey", outcome.jiraIssue().key());
            metadata.put("jiraUrl", outcome.jiraIssue().browseUrl());
        }
        return context(sb.toString(), metadata, json(outcome));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** The ServiceNow number named in the question, if any. */
    Optional<String> extractTicketNumber(String question) {
        if (question == null) {
            return Optional.empty();
        }
        Matcher matcher = TICKET_NUMBER.matcher(question);
        return matcher.find() ? Optional.of(matcher.group(1).toUpperCase(Locale.ROOT)) : Optional.empty();
    }

    /** True when the question restricts to today. */
    boolean mentionsToday(String question) {
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT);
        return text.contains("today") || text.contains("today's") || text.contains("so far today");
    }

    private String render(ServiceNowTicket ticket) {
        StringBuilder sb = new StringBuilder();
        sb.append("  serviceNowTicketNumber: ").append(ticket.id()).append('\n');
        sb.append("  summary: ").append(nullSafe(ticket.title())).append('\n');
        sb.append("  issueType: ").append(nullSafe(ticket.issueType())).append('\n');
        sb.append("  projectKey: ").append(nullSafe(ticket.projectKey())).append('\n');
        if (ticket.priority() != null) {
            sb.append("  priority: ").append(ticket.priority()).append('\n');
        }
        if (ticket.category() != null) {
            sb.append("  category: ").append(ticket.category()).append('\n');
        }
        if (ticket.createdAt() != null) {
            sb.append("  createdAt: ").append(ticket.createdAt()).append('\n');
        }
        return sb.toString();
    }

    private String nullSafe(String value) {
        return value == null || value.isBlank() ? "(not set)" : value;
    }

    private ToolResult context(String summary, Map<String, Object> metadata, String rawPayload) {
        Map<String, Object> full = new LinkedHashMap<>(metadata);
        full.put(STRICT_GROUNDING_FLAG, true);
        return ToolResult.ok(TOOL_NAME, summary, rawPayload, full);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.debug("[{}] Could not serialise payload", TOOL_NAME, ex);
            return String.valueOf(value);
        }
    }
}
