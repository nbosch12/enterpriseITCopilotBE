package com.bosch.demo.docgrounding.controller;

import com.bosch.demo.docgrounding.model.MongoGroundingQueryRequest;
import com.bosch.demo.docgrounding.model.MongoGroundingSyncResult;
import com.bosch.demo.docgrounding.service.CosmosMongoGroundingService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/mongodb")
@ConditionalOnProperty(prefix = "app.cosmos-mongo", name = "enabled", havingValue = "true")
public class CosmosMongoGroundingController {

    private final CosmosMongoGroundingService groundingService;

    public CosmosMongoGroundingController(CosmosMongoGroundingService groundingService) {
        this.groundingService = groundingService;
    }

    @GetMapping("/query-types")
    public List<String> queryTypes() {
        return Arrays.stream(CosmosMongoGroundingService.QueryType.values()).map(Enum::name).toList();
    }

    @PostMapping("/grounding/prepare")
    public Mono<MongoGroundingSyncResult> prepare(@Valid @RequestBody MongoGroundingQueryRequest request) {
        // Mongo driver + S3 client are blocking; keep work off WebFlux event-loop threads.
        return Mono.fromCallable(() -> groundingService.prepareGroundingData(request))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
