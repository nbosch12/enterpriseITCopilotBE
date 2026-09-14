package com.bosch.demo.docgrounding.controller;

import com.bosch.demo.docgrounding.model.LogQueryResult;
import com.bosch.demo.docgrounding.service.AzureSpringAppLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/ops")
public class OpsLogController {

    private final AzureSpringAppLogService azureSpringAppLogService;

    public OpsLogController(AzureSpringAppLogService azureSpringAppLogService) {
        this.azureSpringAppLogService = azureSpringAppLogService;
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
            @RequestParam(required = false) String keywordToSearch) {

        log.info("Fetching exceptions for today. App: {}, Limit: {}", appName, limit);
        return azureSpringAppLogService.getAppLogs(appName, limit, keywordToSearch);
    }
}

