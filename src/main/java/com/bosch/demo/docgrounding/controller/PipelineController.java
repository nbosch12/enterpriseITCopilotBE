package com.bosch.demo.docgrounding.controller;

import com.bosch.demo.docgrounding.jira.model.JiraIssueResponse;
import com.bosch.demo.docgrounding.pipeline.TicketHandoffService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@RestController
@RequestMapping("/api/pipeline")
public class PipelineController {

    private final TicketHandoffService ticketHandoffService;

    public PipelineController(TicketHandoffService ticketHandoffService) {
        this.ticketHandoffService = ticketHandoffService;
    }

    /**
     * POST /api/pipeline/trigger
     * Manually trigger the ServiceNow → AI → Jira pipeline.
     * Runs on a bounded-elastic thread to allow blocking calls (Jira, ServiceNow, OpenAI).
     */
    @PostMapping("/trigger")
    public Mono<List<JiraIssueResponse>> trigger() {
        return Mono.fromCallable(ticketHandoffService::processAll)
                   .subscribeOn(Schedulers.boundedElastic());
    }
}
