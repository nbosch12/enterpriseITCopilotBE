package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.config.AppProperties;
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

    private final WebClient webClient;
    private final AppProperties properties;
    private final AiCoreTokenService tokenService;
    private final ObjectMapper objectMapper;

    public AiCoreVectorSearchService(
            WebClient.Builder builder,
            AppProperties properties,
            AiCoreTokenService tokenService,
            ObjectMapper objectMapper) {

        this.properties = properties;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;

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
                    ""));
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
                        ""));
            }
        }

        int topK = sanitizeBound(request.topK(), DEFAULT_TOP_K, 1, 20);

        return tokenService.getAccessToken()
                .flatMap(accessToken ->
                    Mono.fromCallable(() -> queryWithGrounding(
                            accessToken,
                            request.question(),
                            repositoryId,
                            topK))
                            .subscribeOn(Schedulers.boundedElastic()))
                .onErrorResume(ex -> {
                    log.error("Vector search failed", ex);
                    return Mono.just(new VectorAskResponse(
                            "Vector search failed: " + ex.getMessage(),
                            List.of(),
                            ""));
                });
    }

    private VectorAskResponse queryWithGrounding(
            String accessToken,
            String question,
            String repositoryId,
            int topK) {

        try {
            AppProperties.SapAiCore ai = properties.getSapAiCore();
            String resourceGroup = ai.getResourceGroup();
            String orchestrationDeploymentId = ai.getOrchestrationDeploymentId();

            if (orchestrationDeploymentId == null || orchestrationDeploymentId.isBlank()) {
                return new VectorAskResponse(
                        "Grounding search failed: orchestration-deployment-id must be configured",
                        List.of(),
                        "");
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

            // --- Templating module: prompt template with placeholders ---
            List<Map<String, Object>> promptTemplate = List.of(
                    Map.of("role", "system", "content",
                            "Answer only from the grounded document context below.\n" +
                            "{{?grounding_output}}\n" +
                            "If the context does not contain enough information, say so."),
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

            return new VectorAskResponse(answer, matches, responseBody);

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

