package com.bosch.demo.docgrounding.controller;

import com.bosch.demo.docgrounding.servicenow.ServiceNowTicketService;
import com.bosch.demo.docgrounding.servicenow.model.ServiceNowTicket;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Create and inspect mock ServiceNow tickets.
 *
 * <p>Nothing here talks to a real ServiceNow instance; tickets live in memory for the lifetime of
 * the application. Creating a ticket does not raise a Jira issue — that happens only when asked
 * for, through the copilot or {@code POST /api/servicenow/tickets/{number}/jira}.</p>
 */
@RestController
@RequestMapping("/api/servicenow")
public class MockServiceNowController {

    private static final Logger log = LoggerFactory.getLogger(MockServiceNowController.class);

    private final ServiceNowTicketService ticketService;

    public MockServiceNowController(ServiceNowTicketService ticketService) {
        this.ticketService = ticketService;
    }

    /**
     * Create a mock ServiceNow ticket.
     *
     * <pre>
     * POST /api/servicenow/tickets
     * {
     *   "number":      "INC1000123",
     *   "summary":     "Payment service returning 500s",
     *   "description": "Checkout fails intermittently since the 09:00 deploy.",
     *   "issueType":   "Bug",
     *   "projectKey":  "ITSUP",
     *   "category":    "Application",
     *   "priority":    "HIGH",
     *   "requestedBy": "prem@example.com"
     * }
     * </pre>
     *
     * {@code number} and {@code createdAt} are generated when omitted.
     */
    @PostMapping("/tickets")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateTicketRequest request) {
        ServiceNowTicket stored = ticketService.save(request.toTicket());
        log.info("Mock ServiceNow ticket created: {}", stored.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(describe(stored));
    }

    /** Every known mock ticket, posted and seeded, newest first. */
    @GetMapping("/tickets")
    public List<Map<String, Object>> list() {
        return ticketService.findAll().stream().map(this::describe).toList();
    }

    /** Mock tickets created today, in the configured ServiceNow timezone. */
    @GetMapping("/tickets/today")
    public Map<String, Object> today() {
        List<ServiceNowTicket> tickets = ticketService.findCreatedToday();
        return Map.of(
                "date", ticketService.today().toString(),
                "timezone", ticketService.zone().getId(),
                "count", tickets.size(),
                "tickets", tickets.stream().map(this::describe).toList());
    }

    /** One mock ticket by its ServiceNow number. */
    @GetMapping("/tickets/{number}")
    public ResponseEntity<Map<String, Object>> byNumber(@PathVariable String number) {
        return ticketService.findByNumber(number)
                .map(t -> ResponseEntity.ok(describe(t)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "No mock ServiceNow ticket named " + number + " exists.")));
    }

    /**
     * Raise a Jira issue for one mock ticket, without going through the copilot. Useful for
     * testing the hand-off directly.
     */
    @PostMapping("/tickets/{number}/jira")
    public Mono<ResponseEntity<Map<String, Object>>> createJira(@PathVariable String number) {
        return Mono.fromCallable(() -> {
                    ServiceNowTicketService.Outcome outcome = ticketService.createJiraIssue(number);
                    Map<String, Object> body = new java.util.LinkedHashMap<>();
                    body.put("status", outcome.status().name());
                    body.put("message", outcome.message());
                    if (outcome.ticket() != null) {
                        body.put("serviceNowTicketNumber", outcome.ticket().id());
                    }
                    if (outcome.jiraIssue() != null) {
                        body.put("jiraKey", outcome.jiraIssue().key());
                        body.put("jiraUrl", outcome.jiraIssue().browseUrl());
                    }
                    return ResponseEntity.status(httpStatus(outcome)).body(body);
                })
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    private HttpStatus httpStatus(ServiceNowTicketService.Outcome outcome) {
        return switch (outcome.status()) {
            case CREATED -> HttpStatus.CREATED;
            case ALREADY_CREATED -> HttpStatus.OK;
            case TICKET_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case TICKET_NUMBER_MISSING -> HttpStatus.BAD_REQUEST;
            case JIRA_FAILED -> HttpStatus.BAD_GATEWAY;
        };
    }

    private Map<String, Object> describe(ServiceNowTicket ticket) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("number", ticket.id());
        body.put("summary", ticket.title());
        body.put("issueType", ticket.issueType());
        body.put("projectKey", ticket.projectKey());
        body.put("category", ticket.category());
        body.put("priority", ticket.priority());
        body.put("requestedBy", ticket.requestedBy());
        body.put("description", ticket.description());
        body.put("createdAt", ticket.createdAt() == null ? null : ticket.createdAt().toString());
        return body;
    }

    /**
     * Request body for creating a mock ticket. Field names follow ServiceNow wording
     * ({@code number}, {@code summary}) rather than the internal record's field names.
     */
    public record CreateTicketRequest(
            String number,
            @NotBlank String summary,
            String description,
            String issueType,
            String projectKey,
            String category,
            String priority,
            String requestedBy,
            @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDateTime createdAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate createdOn) {

        ServiceNowTicket toTicket() {
            // createdOn lets a caller place a ticket on a given day without inventing a clock time.
            LocalDateTime created = createdAt != null
                    ? createdAt
                    : (createdOn != null ? createdOn.atTime(9, 0) : LocalDateTime.now());

            return new ServiceNowTicket(
                    number,
                    null,
                    summary,
                    description,
                    category,
                    priority,
                    requestedBy,
                    created,
                    issueType,
                    projectKey);
        }
    }
}
