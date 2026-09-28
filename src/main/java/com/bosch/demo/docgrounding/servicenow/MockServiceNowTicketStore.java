package com.bosch.demo.docgrounding.servicenow;

import com.bosch.demo.docgrounding.servicenow.model.ServiceNowTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory store of mock ServiceNow tickets.
 *
 * <p>Holds the tickets posted at runtime and, alongside them, the demo tickets
 * {@link MockServiceNowClient} already seeds. Lookups see both, so a question about "tickets
 * created today" covers the seeded demo data as well as anything the user just created.</p>
 *
 * <p>The store is deliberately separate from {@link MockServiceNowClient#fetchNewTickets()}: that
 * method feeds the polling scheduler, which raises a Jira issue for everything it returns. Posting
 * a mock ticket must not cause a Jira issue to appear by itself, so posted tickets live here and
 * the scheduler's view is left exactly as it was.</p>
 *
 * <p>State is per-instance and lost on restart, which is what a mock should be.</p>
 */
@Component
public class MockServiceNowTicketStore {

    private static final Logger log = LoggerFactory.getLogger(MockServiceNowTicketStore.class);

    /** Ticket number prefix used when a caller does not supply one. */
    private static final String GENERATED_PREFIX = "INC";
    private static final long GENERATED_START = 1000000L;

    private final MockServiceNowClient mockClient;
    private final Map<String, ServiceNowTicket> posted = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(GENERATED_START);

    public MockServiceNowTicketStore(MockServiceNowClient mockClient) {
        this.mockClient = mockClient;
    }

    /**
     * Store a mock ticket, filling in the fields the caller left out.
     *
     * @param ticket the ticket to store; {@code id} and {@code createdAt} are generated when absent
     * @return the stored ticket, with generated values applied
     */
    public ServiceNowTicket save(ServiceNowTicket ticket) {
        String id = normalise(ticket.id());
        if (id.isEmpty()) {
            id = GENERATED_PREFIX + sequence.incrementAndGet();
        }
        String sysId = normalise(ticket.sysId());
        if (sysId.isEmpty()) {
            sysId = "mock-sys-" + id.toLowerCase(Locale.ROOT);
        }

        ServiceNowTicket stored = new ServiceNowTicket(
                id,
                sysId,
                ticket.title(),
                ticket.description(),
                ticket.category(),
                ticket.priority(),
                ticket.requestedBy(),
                ticket.createdAt() == null ? LocalDateTime.now() : ticket.createdAt(),
                ticket.issueType(),
                ticket.projectKey());

        posted.put(key(id), stored);
        log.info("Mock ServiceNow: stored ticket {} ({} total posted).", id, posted.size());
        return stored;
    }

    /**
     * Find one ticket by its ServiceNow number, searching posted tickets first and then the seeded
     * demo tickets. Matching ignores case and surrounding whitespace.
     */
    public Optional<ServiceNowTicket> findByNumber(String ticketNumber) {
        String wanted = key(normalise(ticketNumber));
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        ServiceNowTicket fromPosted = posted.get(wanted);
        if (fromPosted != null) {
            return Optional.of(fromPosted);
        }
        return mockClient.allSeededTickets().stream()
                .filter(t -> key(normalise(t.id())).equals(wanted))
                .findFirst();
    }

    /**
     * Every known mock ticket, posted and seeded, newest first. A posted ticket takes precedence
     * over a seeded one with the same number.
     */
    public List<ServiceNowTicket> findAll() {
        List<ServiceNowTicket> all = new ArrayList<>(posted.values());
        for (ServiceNowTicket seeded : mockClient.allSeededTickets()) {
            if (!posted.containsKey(key(normalise(seeded.id())))) {
                all.add(seeded);
            }
        }
        all.sort(Comparator.comparing(
                ServiceNowTicket::createdAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return all;
    }

    /**
     * Tickets whose creation timestamp falls on the given day in the given zone.
     *
     * <p>Timestamps are stored as {@link LocalDateTime}, so they are read as wall-clock time in the
     * configured zone rather than converted. That keeps "today" meaning the same day the user is
     * looking at.</p>
     */
    public List<ServiceNowTicket> findCreatedOn(LocalDate day, ZoneId zone) {
        if (day == null) {
            return List.of();
        }
        return findAll().stream()
                .filter(t -> t.createdAt() != null)
                .filter(t -> t.createdAt().atZone(zone == null ? ZoneId.systemDefault() : zone)
                        .toLocalDate().equals(day))
                .toList();
    }

    /** Number of tickets posted at runtime, for diagnostics. */
    public int postedCount() {
        return posted.size();
    }

    /** Clears posted tickets. Intended for tests; seeded demo tickets are unaffected. */
    public void clearPosted() {
        posted.clear();
    }

    private String normalise(String value) {
        return value == null ? "" : value.trim();
    }

    private String key(String value) {
        return value.toUpperCase(Locale.ROOT);
    }
}
