package com.bosch.demo.docgrounding.scheduler;

import com.bosch.demo.docgrounding.pipeline.TicketHandoffService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "pipeline.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class ServiceNowPollingScheduler {

    private static final Logger log = LoggerFactory.getLogger(ServiceNowPollingScheduler.class);

    private final TicketHandoffService ticketHandoffService;

    public ServiceNowPollingScheduler(TicketHandoffService ticketHandoffService) {
        this.ticketHandoffService = ticketHandoffService;
    }

    @Scheduled(
        fixedDelayString   = "${pipeline.poll-interval-ms:3600000}",
        initialDelayString = "${pipeline.poll-interval-ms:3600000}"
    )
    public void poll() {
        log.info("Scheduler triggered — polling ServiceNow for new tickets...");
        var created = ticketHandoffService.processAll();
        log.info("Scheduler poll complete — {} new Jira ticket(s) created.", created.size());
    }
}
