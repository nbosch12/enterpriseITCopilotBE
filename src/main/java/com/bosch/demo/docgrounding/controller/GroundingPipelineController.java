package com.bosch.demo.docgrounding.controller;

import com.bosch.demo.docgrounding.model.CreatePipelineRequest;
import com.bosch.demo.docgrounding.service.AiCoreGroundingService;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/grounding")
public class GroundingPipelineController {
    private final AiCoreGroundingService groundingService;

    public GroundingPipelineController(AiCoreGroundingService groundingService) {
        this.groundingService = groundingService;
    }

    @PostMapping("/pipeline")
    public Mono<String> createPipeline(@Valid @RequestBody CreatePipelineRequest request) {
        return groundingService.createS3Pipeline(request.includePath());
    }

    @GetMapping("/pipeline/{pipelineId}/status")
    public Mono<String> status(@PathVariable String pipelineId) {
        return groundingService.getPipelineStatus(pipelineId);
    }
}
