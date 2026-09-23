package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.config.AppProperties;
import com.bosch.demo.docgrounding.model.CopilotAskRequest;
import com.bosch.demo.docgrounding.model.CopilotAskResponse;
import com.bosch.demo.docgrounding.model.ToolRequest;
import com.bosch.demo.docgrounding.model.ToolResult;
import com.bosch.demo.docgrounding.service.tools.CopilotTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Main orchestration service that routes questions to appropriate tools and synthesizes final answers.
 *
 * <p>Core workflow:
 * 1. Analyze the user question
 * 2. Select appropriate tool(s) based on keywords or force directive
 * 3. Execute selected tools in parallel
 * 4. Combine tool results into unified context
 * 5. Generate final synthesized response via LLM
 * 6. Return structured response with traceability
 */
@Slf4j
@Service
public class CopilotOrchestrationService {

    private final ToolRegistryService toolRegistry;
    private final SapAiCoreIncidentLlmClient llmClient;
    private final ConversationMemoryService conversationMemory;
    private final AppProperties appProperties;

    public CopilotOrchestrationService(ToolRegistryService toolRegistry,
                                       SapAiCoreIncidentLlmClient llmClient,
                                       ConversationMemoryService conversationMemory,
                                       AppProperties appProperties) {
        this.toolRegistry = toolRegistry;
        this.llmClient = llmClient;
        this.conversationMemory = conversationMemory;
        this.appProperties = appProperties;
    }

    /**
     * Process a user question through the full orchestration pipeline.
     */
    public Mono<CopilotAskResponse> ask(CopilotAskRequest request) {
        log.info("Processing copilot question: '{}' (session: {})", request.question(), request.sessionId());

        List<String> processingNotes = new ArrayList<>();
        
        return selectTools(request, processingNotes)
                .flatMap(selectedTools -> {
                    if (selectedTools.isEmpty()) {
                        processingNotes.add("No suitable tools found for this question");
                        return Mono.just(buildFallbackResponse(request, processingNotes));
                    }
                    
                    return executeTools(request, selectedTools)
                            .collectList()
                            .flatMap(toolResults -> synthesizeResponse(request, toolResults, processingNotes));
                });
    }

    // -------------------------------------------------------------------------
    // Tool Selection
    // -------------------------------------------------------------------------

    private Mono<List<CopilotTool>> selectTools(CopilotAskRequest request, List<String> processingNotes) {
        // Force tool directive takes precedence
        if (request.forceTool() != null && !request.forceTool().isBlank()) {
            return toolRegistry.findByName(request.forceTool())
                    .map(tool -> {
                        processingNotes.add("Forced tool: " + tool.name());
                        return List.of(tool);
                    })
                    .map(Mono::just)
                    .orElse(Mono.just(List.of()));
        }

        // Heuristic keyword-based selection
        List<CopilotTool> candidates = toolRegistry.findCandidateTools(request.question());
        
        if (candidates.isEmpty()) {
            processingNotes.add("No keyword matches found");
            return Mono.just(List.of());
        }

        // For now, take top 2 candidates to avoid overwhelming context
        List<CopilotTool> selected = candidates.stream().limit(2).collect(Collectors.toList());
        processingNotes.add("Selected tools: " + selected.stream().map(CopilotTool::name).collect(Collectors.joining(", ")));
        
        return Mono.just(selected);
    }

    // -------------------------------------------------------------------------
    // Tool Execution
    // -------------------------------------------------------------------------

    private Flux<ToolResult> executeTools(CopilotAskRequest request, List<CopilotTool> tools) {
        Map<String, Object> toolParams = buildToolParameters(request);
        ToolRequest toolRequest = new ToolRequest(request.question(), request.sessionId(), toolParams);

        return Flux.fromIterable(tools)
                .flatMap(tool -> {
                    log.debug("Executing tool: {}", tool.name());
                    return tool.execute(toolRequest)
                            .doOnSuccess(result -> log.debug("Tool {} completed: {}", tool.name(), result.success()))
                            .doOnError(error -> log.error("Tool {} failed", tool.name(), error))
                            .onErrorReturn(ToolResult.error(tool.name(), "Tool execution exception"));
                });
    }

    private Map<String, Object> buildToolParameters(CopilotAskRequest request) {
        Map<String, Object> params = new HashMap<>();
        if (request.appName() != null)      params.put("appName", request.appName());
        if (request.timeDuration() != null) params.put("timeDuration", request.timeDuration());

        // Automatically provide default repositoryId if not specified
        String repositoryId = request.repositoryId() != null && !request.repositoryId().isBlank()
                ? request.repositoryId()
                : appProperties.getDocupedia().getDefaultRepositoryId();
        if (request.repositoryId() == null || request.repositoryId().isBlank()) {
            log.debug("No repositoryId provided in request, using default: {}", repositoryId);
        }
        params.put("repositoryId", repositoryId);

        // Automatically provide default s3Prefix if not specified
        String s3Prefix = request.s3Prefix() != null && !request.s3Prefix().isBlank()
                ? request.s3Prefix()
                : appProperties.getDocupedia().getDefaultS3Prefix();
        if (request.s3Prefix() == null || request.s3Prefix().isBlank()) {
            log.debug("No s3Prefix provided in request, using default: {}", s3Prefix);
        }
        params.put("s3Prefix", s3Prefix);

        if (request.useHistory() != null)   params.put("useHistory", request.useHistory().toString());
        if (request.historyTurns() != null) params.put("historyTurns", request.historyTurns().toString());
        return params;
    }

    // -------------------------------------------------------------------------
    // Response Synthesis
    // -------------------------------------------------------------------------

    private Mono<CopilotAskResponse> synthesizeResponse(CopilotAskRequest request, 
                                                        List<ToolResult> toolResults,
                                                        List<String> processingNotes) {
        // Add conversation history if requested
        String conversationContext = "";
        if (Boolean.TRUE.equals(request.useHistory()) && request.sessionId() != null) {
            int turns = request.historyTurns() != null ? request.historyTurns() : 6;
            var history = conversationMemory.getRecentTurns(request.sessionId(), turns);
            if (!history.isEmpty()) {
                conversationContext = history.stream()
                        .map(turn -> turn.role() + ": " + turn.content())
                        .collect(Collectors.joining("\n"));
                conversationContext = "Previous conversation:\n" + conversationContext + "\n\n";
            }
        }

        String combinedContext = buildCombinedContext(request.question(), toolResults, conversationContext);
        
        return llmClient.summarizeIncident(combinedContext)
                .map(answer -> {
                    // Save to conversation memory
                    if (request.sessionId() != null) {
                        conversationMemory.addUserTurn(request.sessionId(), request.question());
                        conversationMemory.addAssistantTurn(request.sessionId(), answer);
                    }

                    return new CopilotAskResponse(
                            answer,
                            toolResults.stream().map(ToolResult::toolName).collect(Collectors.toList()),
                            toolResults,
                            request.sessionId(),
                            processingNotes
                    );
                })
                .onErrorReturn(buildErrorResponse(request, toolResults, processingNotes));
    }

    private String buildCombinedContext(String question, List<ToolResult> toolResults, String conversationContext) {
        StringBuilder context = new StringBuilder();
        
        context.append("You are an enterprise IT support assistant. Answer the user's question based on the following context.\n\n");
        
        if (!conversationContext.isBlank()) {
            context.append(conversationContext);
        }
        
        context.append("User Question: ").append(question).append("\n\n");
        
        context.append("Available Information:\n");
        for (ToolResult result : toolResults) {
            if (result.success() && result.summary() != null && !result.summary().isBlank()) {
                context.append("From ").append(result.toolName()).append(":\n");
                context.append(result.summary()).append("\n\n");
            }
        }
        
        context.append("Please provide a comprehensive answer that synthesizes this information. Be specific and actionable.");
        
        return context.toString();
    }

    // -------------------------------------------------------------------------
    // Fallback Responses
    // -------------------------------------------------------------------------

    private CopilotAskResponse buildFallbackResponse(CopilotAskRequest request, List<String> processingNotes) {
        return new CopilotAskResponse(
                "I'm not sure how to help with that question. Please try rephrasing or specify if you need help with logs, documentation, or a specific system.",
                List.of(),
                List.of(),
                request.sessionId(),
                processingNotes
        );
    }

    private CopilotAskResponse buildErrorResponse(CopilotAskRequest request, List<ToolResult> toolResults, List<String> processingNotes) {
        processingNotes.add("LLM synthesis failed - returning partial results");
        
        // Build a simple fallback answer from tool summaries
        String fallbackAnswer = toolResults.stream()
                .filter(ToolResult::success)
                .map(ToolResult::summary)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n\n"));
        
        if (fallbackAnswer.isBlank()) {
            fallbackAnswer = "I encountered an error processing your request, but no usable information was retrieved.";
        }

        return new CopilotAskResponse(
                fallbackAnswer,
                toolResults.stream().map(ToolResult::toolName).collect(Collectors.toList()),
                toolResults,
                request.sessionId(),
                processingNotes
        );
    }
}
