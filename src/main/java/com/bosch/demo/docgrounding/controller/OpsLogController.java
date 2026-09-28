package com.bosch.demo.docgrounding.controller;

import com.bosch.demo.docgrounding.model.IncidentSummaryRequest;
import com.bosch.demo.docgrounding.model.IncidentSummaryResponse;
import com.bosch.demo.docgrounding.model.LogQueryResult;
import com.bosch.demo.docgrounding.service.AzureSpringAppLogService;
import com.bosch.demo.docgrounding.service.OpsIncidentSummaryService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/ops")
public class OpsLogController {

    private final AzureSpringAppLogService azureSpringAppLogService;
    private final OpsIncidentSummaryService opsIncidentSummaryService;

    public OpsLogController(
            AzureSpringAppLogService azureSpringAppLogService,
            OpsIncidentSummaryService opsIncidentSummaryService) {
        this.azureSpringAppLogService = azureSpringAppLogService;
        this.opsIncidentSummaryService = opsIncidentSummaryService;
    }

    /**
     * Get exceptions from Azure Spring Apps for today
     *
     * @param appName optional filter for specific app name
     * @param limit   maximum number of latest exceptions to return (default: 10)
     * @return Mono with log query result containing exception details
     */
    @GetMapping("/logs")
    public Mono<LogQueryResult> getApplicationLogs(
            @RequestParam(required = false) String appName,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String keywordToSearch,
            @RequestParam(required = false, defaultValue = "1h") String timeDuration) {

        log.info("Fetching exceptions. App: {}, Limit: {}, TimeDuration: {}", appName, limit, timeDuration);
        return azureSpringAppLogService.getAppLogs(appName, limit, keywordToSearch, timeDuration);
    }

    @PostMapping("/logs/summary")
    public Mono<IncidentSummaryResponse> summarizeApplicationLogs(
            @Valid @RequestBody IncidentSummaryRequest request) {

        log.info("Summarizing logs for question='{}', app='{}', timeDuration='{}'",
                request.question(), request.appName(), request.timeDuration());
        return opsIncidentSummaryService.summarizeIncident(request);
    }
}

