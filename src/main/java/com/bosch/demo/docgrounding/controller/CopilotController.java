package com.bosch.demo.docgrounding.controller;

import com.bosch.demo.docgrounding.model.CopilotAskRequest;
import com.bosch.demo.docgrounding.model.CopilotAskResponse;
import com.bosch.demo.docgrounding.service.CopilotOrchestrationService;
import com.bosch.demo.docgrounding.service.ToolRegistryService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * Unified orchestration controller that serves as the single entry point for all copilot queries.
 *
 * <p>This is the main API endpoint that frontend applications should call. The orchestrator
 * will intelligently route requests to appropriate backend tools (logs, grounding, future integrations)
 * and return a synthesized response.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/copilot/ask} - Main question answering endpoint</li>
 *   <li>{@code GET /api/copilot/tools} - List available tools (for debugging/UI)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/copilot")
public class CopilotController {

    private final CopilotOrchestrationService orchestrationService;
    private final ToolRegistryService toolRegistry;

    public CopilotController(CopilotOrchestrationService orchestrationService,
                             ToolRegistryService toolRegistry) {
        this.orchestrationService = orchestrationService;
        this.toolRegistry = toolRegistry;
    }

    /**
     * Main orchestrated question-answering endpoint.
     *
     * <p>Example request:
     * <pre>
     * POST /api/copilot/ask
     * {
     *   "question": "Summarize today's production issues and suggest next steps",
     *   "sessionId": "user-123",
     *   "useHistory": true
     * }
     * </pre>
     *
     * <p>The orchestrator will:
     * <ol>
     *   <li>Analyze the question and select appropriate tools</li>
     *   <li>Execute selected tools in parallel</li>
     *   <li>Combine results and synthesize a unified answer</li>
     *   <li>Return structured response with traceability</li>
     * </ol>
     *
     * @param request the copilot ask request
     * @return structured response with answer, tools used, and debug info
     */
    @PostMapping("/ask")
    public Mono<CopilotAskResponse> ask(@Valid @RequestBody CopilotAskRequest request) {
        log.info("Received copilot question: '{}'", request.question());
        return orchestrationService.ask(request)
                .doOnSuccess(response -> log.info("Copilot response generated using tools: {}", 
                        String.join(", ", response.toolsUsed())))
                .doOnError(error -> log.error("Copilot request failed", error));
    }

    /**
     * List all available tools with their descriptions.
     *
     * <p>Useful for debugging, monitoring, or building dynamic UI components that show
     * which backend systems are available.
     *
     * @return formatted string describing all registered tools
     */
    @GetMapping("/tools")
    public Mono<String> listTools() {
        String toolDescriptions = toolRegistry.getToolDescriptions();
        log.debug("Tool descriptions requested: {} tools available", toolRegistry.getAllTools().size());
        return Mono.just(toolDescriptions);
    }

    /**
     * Health check endpoint to verify orchestration layer is working.
     *
     * @return simple status message
     */
    @GetMapping("/health")
    public Mono<String> health() {
        int toolCount = toolRegistry.getAllTools().size();
        return Mono.just("Copilot orchestration layer is healthy. " + toolCount + " tools registered.");
    }
}
