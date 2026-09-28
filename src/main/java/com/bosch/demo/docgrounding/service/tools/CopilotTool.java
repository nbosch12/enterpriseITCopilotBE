package com.bosch.demo.docgrounding.service.tools;

import com.bosch.demo.docgrounding.model.ToolRequest;
import com.bosch.demo.docgrounding.model.ToolResult;
import reactor.core.publisher.Mono;

/**
 * Contract that every orchestration tool must implement.
 *
 * <p>The {@link com.bosch.demo.docgrounding.service.ToolRegistryService} discovers all
 * Spring-managed beans of this type at startup and registers them automatically.</p>
 */
public interface CopilotTool {

    /**
     * Unique, uppercase identifier used for routing and UI display.
     * Examples: "AZURE_LOGS", "DOCUPEDIA_GROUNDING".
     */
    String name();

    /**
     * Short human-readable description used in LLM tool-selection prompts and logs.
     */
    String description();

    /**
     * Keyword hints used by the heuristic router.
     * If the user question contains any of these tokens the tool is a candidate.
     */
    String[] keywords();

    /**
     * Execute the tool for the given request and return a normalised {@link ToolResult}.
     * Implementations must never throw – errors must be returned as {@link ToolResult#error}.
     */
    Mono<ToolResult> execute(ToolRequest request);
}
