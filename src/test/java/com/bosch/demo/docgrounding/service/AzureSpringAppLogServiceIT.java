package com.bosch.demo.docgrounding.service;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration test for Azure Spring App Log Service
 * Note: Requires Azure Monitor to be configured in application.yml
 * Run with: mvn test -Dtest=AzureSpringAppLogServiceIT
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
public class AzureSpringAppLogServiceIT {

    @Autowired
    private AzureSpringAppLogService azureSpringAppLogService;

    /**
     * Test fetching exceptions from Azure
     * This test requires actual Azure setup and will be skipped if Azure Monitor is disabled
     */
    @Test
    public void testGetExceptionsToday() {
        log.info("Testing exception retrieval from Azure...");

        StepVerifier.create(azureSpringAppLogService.getAppLogs(null, 10, "test", "1h"))
                .assertNext(result -> {
                    assertNotNull(result);
                    log.info("Query result: Total exceptions={}, Apps={}",
                             result.getTotalCount(),
                             result.getCountByApp().size());
                    log.info("Count by app: {}", result.getCountByApp());

                    if (result.getLatestExceptions() != null && !result.getLatestExceptions().isEmpty()) {
                        log.info("Latest exception: {}", result.getLatestExceptions().getFirst());
                    }
                })
                .verifyComplete();
    }

    /**
     * Test filtering by app name
     */
    @Test
    public void testGetExceptionsByAppName() {
        log.info("Testing exception retrieval filtered by app name...");

        StepVerifier.create(azureSpringAppLogService.getAppLogs("my-app", 5, "test", "1h"))
                .assertNext(result -> {
                    assertNotNull(result);
                    log.info("Filtered results for app 'my-app': {}", result.getTotalCount());
                })
                .verifyComplete();
    }

    /**
     * Test with custom limit
     */
    @Test
    public void testGetExceptionsWithLimit() {
        log.info("Testing exception retrieval with custom limit...");

        StepVerifier.create(azureSpringAppLogService.getAppLogs(null, 20, "test", "1h"))
                .assertNext(result -> {
                    assertNotNull(result);
                    assert result.getLatestExceptions().size() <= 20;
                    log.info("Retrieved {} exceptions (limit: 20)", result.getLatestExceptions().size());
                })
                .verifyComplete();
    }
}

