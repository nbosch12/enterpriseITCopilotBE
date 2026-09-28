package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.config.AppProperties;
import com.bosch.demo.docgrounding.model.ConversationTurn;
import com.bosch.demo.docgrounding.model.VectorAskRequest;
import com.bosch.demo.docgrounding.model.VectorAskResponse;
import com.bosch.demo.docgrounding.model.VectorMatch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@Slf4j
public class AiCoreVectorSearchService {

    private static final int DEFAULT_TOP_K = 5;
    private static final int DEFAULT_HISTORY_TURNS = 6;

    private final WebClient webClient;
    private final AppProperties properties;
    private final AiCoreTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final ConversationMemoryService conversationMemoryService;

    public AiCoreVectorSearchService(
            WebClient.Builder builder,
            AppProperties properties,
            AiCoreTokenService tokenService,
            ObjectMapper objectMapper,
            ConversationMemoryService conversationMemoryService) {

        this.properties = properties;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.conversationMemoryService = conversationMemoryService;

        String apiUrl = properties.getSapAiCore().getApiUrl();
        if (apiUrl == null || apiUrl.isBlank()) {
            throw new IllegalStateException("app.sap-ai-core.api-url must be configured");
        }

        this.webClient = builder.baseUrl(apiUrl).build();
    }

    public Mono<VectorAskResponse> ask(VectorAskRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            return Mono.just(new VectorAskResponse(
                    "Vector search failed: question must not be empty",
                    List.of(),
                    "",
                    null));
        }

        // Determine repository ID - prefer repositoryId, fallback to s3Prefix
        final String repositoryId;
        String reqRepositoryId = request.repositoryId();
        if (reqRepositoryId != null && !reqRepositoryId.isBlank()) {
            repositoryId = reqRepositoryId;
        } else {
            String s3Prefix = request.s3Prefix();
            if (s3Prefix != null && !s3Prefix.isBlank()) {
                repositoryId = s3Prefix;
            } else {
                return Mono.just(new VectorAskResponse(
                        "Vector search failed: repositoryId or s3Prefix must be provided",
                        List.of(),
                        "",
                        request.sessionId()));
            }
        }

        final String sessionId = request.sessionId();
        final boolean useHistory = request.useHistory() == null || request.useHistory();
        final int topK = sanitizeBound(request.topK(), DEFAULT_TOP_K, 1, 20);
        final int historyTurns = sanitizeBound(request.historyTurns(), DEFAULT_HISTORY_TURNS, 0, 20);

        // Load conversation history if enabled
        List<ConversationTurn> history = useHistory && sessionId != null && !sessionId.isBlank()
                ? conversationMemoryService.getRecentTurns(sessionId, historyTurns)
                : List.of();

        String historyText = formatConversationHistory(history);

        return tokenService.getAccessToken()
                .flatMap(accessToken ->
                    Mono.fromCallable(() -> queryWithGrounding(
                            accessToken,
                            request.question(),
                            repositoryId,
                            topK,
                            sessionId,
                            historyText))
                            .subscribeOn(Schedulers.boundedElastic()))
                .map(response -> {
                    // Store the new user question and assistant answer in session history
                    if (sessionId != null && !sessionId.isBlank()) {
                        conversationMemoryService.addUserTurn(sessionId, request.question());
                        conversationMemoryService.addAssistantTurn(sessionId, response.answer());
                    }
                    return response;
                })
                .onErrorResume(ex -> {
                    log.error("Vector search failed", ex);
                    return Mono.just(new VectorAskResponse(
                            "Vector search failed: " + ex.getMessage(),
                            List.of(),
                            "",
                            sessionId));
                });
    }

    private VectorAskResponse queryWithGrounding(
            String accessToken,
            String question,
            String repositoryId,
            int topK,
            String sessionId,
            String conversationHistory) {

        try {
            AppProperties.SapAiCore ai = properties.getSapAiCore();
            String resourceGroup = ai.getResourceGroup();
            String orchestrationDeploymentId = ai.getOrchestrationDeploymentId();

            if (orchestrationDeploymentId == null || orchestrationDeploymentId.isBlank()) {
                return new VectorAskResponse(
                        "Grounding search failed: orchestration-deployment-id must be configured",
                        List.of(),
                        "",
                        sessionId);
            }

            // --- LLM module ---
            Map<String, Object> llmConfig = Map.of(
                    "model_name", ai.getLlmModelName(),
                    "model_params", Map.of("max_tokens", 2000));

            // --- Grounding module: vector search filter ---
            Map<String, Object> searchConfig = Map.of(
                    "max_chunk_count", topK);

            Map<String, Object> groundingFilter = Map.of(
                    "id", "grounding_filter_0",
                    "data_repository_type", "vector",
                    "data_repositories", List.of(repositoryId),
                    "search_config", searchConfig);

            Map<String, Object> groundingModuleConfig = Map.of(
                    "type", "document_grounding_service",
                    "config", Map.of(
                            "input_params", List.of("user_query"),
                            "output_param", "grounding_output",
                            "filters", List.of(groundingFilter)));

            // --- Templating module: prompt template with conversation history and grounding ---
            String systemPrompt = buildSystemPrompt(conversationHistory);
            List<Map<String, Object>> promptTemplate = List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", "{{?user_query}}"));

            Map<String, Object> templatingModuleConfig = Map.of(
                    "template", promptTemplate);

            // --- Orchestration config ---
            Map<String, Object> moduleConfigurations = new java.util.LinkedHashMap<>();
            moduleConfigurations.put("llm_module_config", llmConfig);
            moduleConfigurations.put("templating_module_config", templatingModuleConfig);
            moduleConfigurations.put("grounding_module_config", groundingModuleConfig);

            Map<String, Object> orchestrationConfig = Map.of(
                    "module_configurations", moduleConfigurations);

            // --- Full orchestration request ---
            Map<String, Object> orchestrationRequest = Map.of(
                    "orchestration_config", orchestrationConfig,
                    "input_params", Map.of("user_query", question));

            // Call orchestration API with grounding
            String responseBody = webClient.post()
                    .uri("/v2/inference/deployments/{deploymentId}/completion",
                            orchestrationDeploymentId)
                    .header("AI-Resource-Group", resourceGroup)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(orchestrationRequest)
                    .exchangeToMono(response -> response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> {
                                if (response.statusCode().isError()) {
                                    log.error("Grounding API error. HTTP {}, response: {}",
                                            response.statusCode(), body);
                                    throw new RuntimeException(
                                            "Orchestration API call failed with HTTP " +
                                            response.statusCode() + formatErrorBody(body));
                                }
                                return body;
                            }))
                    .block();

            // Extract answer from grounding response
            String answer = extractAnswer(responseBody);

            // Extract grounding matches/context from response
            List<VectorMatch> matches = extractGroundingMatches(responseBody);

            return new VectorAskResponse(answer, matches, responseBody, sessionId);

        } catch (Exception ex) {
            log.error("Grounding query failed", ex);
            throw new RuntimeException("Grounding search error: " + ex.getMessage(), ex);
        }
    }

    private String extractAnswer(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "No answer generated";
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // SAP AI Core orchestration /completion response wraps content under:
            // orchestration_result.choices[0].message.content
            JsonNode orchResult = root.get("orchestration_result");
            if (orchResult != null) {
                JsonNode choices = orchResult.get("choices");
                if (choices != null && choices.isArray() && !choices.isEmpty()) {
                    JsonNode msg = choices.get(0).get("message");
                    if (msg != null && msg.get("content") != null) {
                        return msg.get("content").asText();
                    }
                }
            }

            // Fallback: top-level choices (plain LLM response)
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode msg = choices.get(0).get("message");
                if (msg != null && msg.get("content") != null) {
                    return msg.get("content").asText();
                }
            }

        } catch (Exception ex) {
            log.warn("Failed to extract answer from response", ex);
        }

        return "Answer generated from grounding retrieval. Check rawModelResponse for details.";
    }

    private List<VectorMatch> extractGroundingMatches(String responseBody) {
        List<VectorMatch> matches = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // SAP AI Core orchestration response: module_results.grounding.data[].chunks[]
            JsonNode moduleResults = root.get("module_results");
            if (moduleResults == null) return matches;

            JsonNode grounding = moduleResults.get("grounding");
            if (grounding == null) return matches;

            JsonNode data = grounding.get("data");
            if (data == null || !data.isArray()) return matches;

            int index = 0;
            for (JsonNode doc : data) {
                if (index >= 5) break;

                String docUri = doc.has("document_uri") ? doc.get("document_uri").asText() : "";
                String pageTitle = doc.has("metadata") && doc.get("metadata").has("pageTitle") ?
                        doc.get("metadata").get("pageTitle").asText() : "";
                String pageId = doc.has("metadata") && doc.get("metadata").has("pageId") ?
                        doc.get("metadata").get("pageId").asText() : "";
                String sourceUrl = doc.has("metadata") && doc.get("metadata").has("sourceUrl") ?
                        doc.get("metadata").get("sourceUrl").asText() : "";

                JsonNode chunks = doc.get("chunks");
                if (chunks != null && chunks.isArray()) {
                    for (JsonNode chunk : chunks) {
                        String text = chunk.has("content") ? chunk.get("content").asText() : "";
                        double score = chunk.has("score") ? chunk.get("score").asDouble() : 0.0;

                        matches.add(new VectorMatch(
                                docUri, pageId, pageTitle, sourceUrl,
                                index, score, excerpt(text, 350)));
                        index++;
                        if (index >= 5) break;
                    }
                } else {
                    matches.add(new VectorMatch(
                            docUri, pageId, pageTitle, sourceUrl, index, 0.0, ""));
                    index++;
                }
            }

        } catch (Exception ex) {
            log.debug("Could not extract grounding matches from response", ex);
        }

        return matches;
    }

    private String excerpt(String text, int maxLength) {
        if (text == null) {
            return "";
        }

        String normalized = text.replaceAll("\\s+", " ").trim();

        if (normalized.length() <= maxLength) {
            return normalized;
        }

        return normalized.substring(0, maxLength) + "...";
    }

    /**
     * Format conversation history into a readable text string for inclusion in the LLM prompt.
     */
    private String formatConversationHistory(List<ConversationTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (ConversationTurn turn : turns) {
            sb.append(turn.role().toUpperCase())
                    .append(": ")
                    .append(turn.content())
                    .append("\n");
        }
        return sb.toString();
    }

    /**
     * Build a system prompt that incorporates conversation history and grounding instructions.
     */
    private String buildSystemPrompt(String conversationHistory) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a context-aware assistant that answers questions based on grounded document context. ");
        prompt.append("Use the conversation history to understand the topic and context, ");
        prompt.append("then provide answers primarily from the grounded document content.\n\n");

        if (conversationHistory != null && !conversationHistory.isBlank()) {
            prompt.append("Conversation History:\n");
            prompt.append(conversationHistory);
            prompt.append("\n");
        }

        prompt.append("Grounded Document Context:\n");
        prompt.append("{{?grounding_output}}\n\n");
        prompt.append("Instructions:\n");
        prompt.append("- Answer based on the grounded documents provided above.\n");
        prompt.append("- If the context does not contain enough information to answer, clearly say so.\n");
        prompt.append("- Maintain consistency with previous answers in the conversation history.\n");
        prompt.append("- Be concise and accurate.");

        return prompt.toString();
    }

    private int sanitizeBound(Integer value, int defaultValue, int min, int max) {
        if (value == null) {
            return defaultValue;
        }

        if (value < min) {
            return min;
        }

        return Math.min(value, max);
    }

    private String formatErrorBody(String body) {
        if (body == null) {
            return "";
        }

        String normalized = body.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return "";
        }

        if (normalized.length() > 500) {
            normalized = normalized.substring(0, 500) + "...";
        }

        return ". Response body: " + normalized;
    }
}

