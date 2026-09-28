package com.bosch.demo.docgrounding.controller;

import com.bosch.demo.docgrounding.model.SyncResult;
import com.bosch.demo.docgrounding.service.DocupediaIngestionService;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/docupedia")
public class DocupediaSyncController {
    private final DocupediaIngestionService ingestionService;

    public DocupediaSyncController(DocupediaIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/sync")
    public Mono<SyncResult> sync(
            @RequestParam String spaceKey,
            @RequestParam(defaultValue = "3") Integer childDepth,
            @RequestParam(defaultValue = "delta") String mode,
            @RequestParam(required = false) String rootPageId) {
        // The ingestion flow is blocking (S3 + .block() calls), so run it off the Netty event loop.
        return Mono.fromCallable(() -> ingestionService.syncSpace(spaceKey, childDepth, rootPageId, mode))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
