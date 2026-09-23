package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class SapAiCoreIncidentLlmClient implements LogIncidentLlmClient {

    private final WebClient webClient;
    private final AppProperties properties;
    private final AiCoreTokenService tokenService;
    private final ObjectMapper objectMapper;

    public SapAiCoreIncidentLlmClient(
            WebClient.Builder builder,
            AppProperties properties,
            AiCoreTokenService tokenService,
            ObjectMapper objectMapper) {
        this.webClient = builder.baseUrl(properties.getSapAiCore().getApiUrl()).build();
        this.properties = properties;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<String> summarizeIncident(String prompt) {
        AppProperties.SapAiCore ai = properties.getSapAiCore();
        if (ai.getApiUrl() == null || ai.getApiUrl().isBlank()) {
            return Mono.error(new IllegalStateException("app.sap-ai-core.api-url must be configured"));
        }
        if (ai.getOrchestrationDeploymentId() == null || ai.getOrchestrationDeploymentId().isBlank()) {
            return Mono.error(new IllegalStateException("app.sap-ai-core.orchestration-deployment-id must be configured"));
        }

        Map<String, Object> llmConfig = Map.of(
                "model_name", ai.getLlmModelName(),
                "model_params", Map.of("max_tokens", 1200));

        List<Map<String, Object>> template = List.of(
                Map.of("role", "system", "content", "You are a production incident analysis assistant. Return JSON only."),
                Map.of("role", "user", "content", "{{?incident_prompt}}")
        );

        Map<String, Object> moduleConfigurations = new LinkedHashMap<>();
        moduleConfigurations.put("llm_module_config", llmConfig);
        moduleConfigurations.put("templating_module_config", Map.of("template", template));

        Map<String, Object> requestBody = Map.of(
                "orchestration_config", Map.of("module_configurations", moduleConfigurations),
                "input_params", Map.of("incident_prompt", prompt));

        return tokenService.getAccessToken()
                .flatMap(accessToken -> webClient.post()
                        .uri("/v2/inference/deployments/{deploymentId}/completion", ai.getOrchestrationDeploymentId())
                        .header("AI-Resource-Group", ai.getResourceGroup())
                        .headers(headers -> headers.setBearerAuth(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(requestBody)
                        .exchangeToMono(response -> response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> {
                                    if (response.statusCode().isError()) {
                                        String message = "SAP AI Core incident summary call failed with HTTP "
                                                + response.statusCode()
                                                + formatErrorBody(body);
                                        log.error(message);
                                        return Mono.error(new IllegalStateException(message));
                                    }
                                    return Mono.just(body);
                                })))
                .map(this::extractContent);
    }

    private String extractContent(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("Empty response from incident summary model");
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode orchestrationResult = root.get("orchestration_result");
            if (orchestrationResult != null) {
                JsonNode choices = orchestrationResult.get("choices");
                if (choices != null && choices.isArray() && !choices.isEmpty()) {
                    JsonNode content = choices.get(0).path("message").path("content");
                    if (!content.isMissingNode() && !content.isNull()) {
                        return content.asText();
                    }
                }
            }

            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode content = choices.get(0).path("message").path("content");
                if (!content.isMissingNode() && !content.isNull()) {
                    return content.asText();
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to extract structured incident summary JSON from AI Core response", ex);
        }

        return responseBody;
    }

    private String formatErrorBody(String body) {
        if (body == null) {
            return "";
        }

        String normalized = body.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return "";
        }

        if (normalized.length() > 700) {
            normalized = normalized.substring(0, 700) + "...";
        }

        return ". Response body: " + normalized;
    }
}

