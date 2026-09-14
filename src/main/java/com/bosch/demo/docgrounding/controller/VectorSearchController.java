package com.bosch.demo.docgrounding.controller;

import com.bosch.demo.docgrounding.model.VectorAskRequest;
import com.bosch.demo.docgrounding.model.VectorAskResponse;
import com.bosch.demo.docgrounding.service.AiCoreVectorSearchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/vector")
public class VectorSearchController {

    private final AiCoreVectorSearchService vectorSearchService;

    public VectorSearchController(AiCoreVectorSearchService vectorSearchService) {
        this.vectorSearchService = vectorSearchService;
    }

    @PostMapping("/ask")
    public Mono<VectorAskResponse> ask(@Valid @RequestBody VectorAskRequest request) {
        return vectorSearchService.ask(request);
    }
}

