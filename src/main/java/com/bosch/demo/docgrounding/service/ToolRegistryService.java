package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.service.tools.CopilotTool;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Registry service that discovers and manages all available {@link CopilotTool} implementations.
 *
 * <p>Auto-discovers all Spring beans implementing {@link CopilotTool} at startup and provides
 * routing functionality based on tool names and keyword matching.</p>
 */
@Slf4j
@Service
public class ToolRegistryService {

    /**
     * -- GETTER --
     *  Get all registered tools.
     */
    @Getter
    private final List<CopilotTool> allTools;
    private Map<String, CopilotTool> toolsByName;

    public ToolRegistryService(@Autowired List<CopilotTool> tools) {
        this.allTools = tools;
    }

    @PostConstruct
    private void init() {
        toolsByName = allTools.stream()
                .collect(Collectors.toMap(CopilotTool::name, tool -> tool));
        
        log.info("Discovered {} copilot tools: {}", 
                allTools.size(), 
                allTools.stream().map(CopilotTool::name).collect(Collectors.joining(", ")));
    }

    /**
     * Find a specific tool by name (case-insensitive).
     */
    public Optional<CopilotTool> findByName(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(toolsByName.get(name.toUpperCase()));
    }

    /**
     * Find tools whose keywords match the given question using simple heuristic matching.
     * 
     * @param question The user's question
     * @return List of candidate tools, ordered by keyword match count (descending)
     */
    public List<CopilotTool> findCandidateTools(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }

        String lowerQuestion = question.toLowerCase(Locale.ROOT);
        
        return allTools.stream()
                .filter(tool -> hasKeywordMatch(tool, lowerQuestion))
                .sorted((a, b) -> Integer.compare(
                        countKeywordMatches(b, lowerQuestion),
                        countKeywordMatches(a, lowerQuestion)
                ))
                .collect(Collectors.toList());
    }

    /**
     * Simple heuristic: a tool is a candidate if the question contains any of its keywords.
     */
    private boolean hasKeywordMatch(CopilotTool tool, String lowerQuestion) {
        return Arrays.stream(tool.keywords())
                .anyMatch(keyword -> lowerQuestion.contains(keyword.toLowerCase(Locale.ROOT)));
    }

    /**
     * Count how many of the tool's keywords appear in the question.
     */
    private int countKeywordMatches(CopilotTool tool, String lowerQuestion) {
        return (int) Arrays.stream(tool.keywords())
                .filter(keyword -> lowerQuestion.contains(keyword.toLowerCase(Locale.ROOT)))
                .count();
    }

    /**
     * Get tool descriptions for logging or LLM context.
     */
    public String getToolDescriptions() {
        return allTools.stream()
                .map(tool -> tool.name() + ": " + tool.description())
                .collect(Collectors.joining("\n"));
    }
}
