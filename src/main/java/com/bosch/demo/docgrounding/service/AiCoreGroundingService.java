package com.bosch.demo.docgrounding.service;

import java.util.List;
import java.util.Map;

import com.bosch.demo.docgrounding.config.AppProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class AiCoreGroundingService {
    private final WebClient webClient;
    private final AppProperties properties;
    private final AiCoreTokenService tokenService;

    public AiCoreGroundingService(WebClient.Builder builder, AppProperties properties, AiCoreTokenService tokenService) {
        this.properties = properties;
        this.tokenService = tokenService;
        this.webClient = builder.baseUrl(properties.getSapAiCore().getApiUrl()).build();
    }

    public Mono<String> createS3Pipeline(String includePath) {
        Map<String, Object> payload = Map.of(
                "type", "S3",
                "configuration", Map.of(
                        "destination", properties.getSapAiCore().getS3GenericSecretName(),
                        "s3", Map.of("includePaths", List.of(includePath))
                )
        );

        return tokenService.getAccessToken()
                .flatMap(accessToken -> webClient.post()
                        .uri("/v2/lm/document-grounding/pipelines")
                        .header("AI-Resource-Group", properties.getSapAiCore().getResourceGroup())
                        .headers(headers -> headers.setBearerAuth(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(payload)
                        .retrieve()
                        .bodyToMono(String.class));
    }

    public Mono<String> getPipelineStatus(String pipelineId) {
        return tokenService.getAccessToken()
                .flatMap(accessToken -> webClient.get()
                        .uri("/v2/lm/document-grounding/pipelines/{pipelineId}/status", pipelineId)
                        .header("AI-Resource-Group", properties.getSapAiCore().getResourceGroup())
                        .headers(headers -> headers.setBearerAuth(accessToken))
                        .retrieve()
                        .bodyToMono(String.class));
    }


    public Mono<String> triggerPipeline(String pipelineId) {

        Map<String, Object> payload = Map.of(
                "pipelineId", pipelineId,
                "metadataOnly", false
        );

        return tokenService.getAccessToken()
                .flatMap(accessToken -> webClient.post()
                        .uri("/v2/lm/document-grounding/pipelines/trigger")
                        .header(
                                "AI-Resource-Group",
                                properties.getSapAiCore().getResourceGroup())
                        .headers(headers ->
                                headers.setBearerAuth(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(payload)
                        .exchangeToMono(response ->
                                response.bodyToMono(String.class)
                                        .defaultIfEmpty(
                                                "{\"status\":\"" +
                                                        response.statusCode() +
                                                        "\"}"
                                        )));
    }

    public Mono<String> getPipelineDocuments(String pipelineId) {

        return tokenService.getAccessToken()
                .flatMap(accessToken -> webClient.get()
                        .uri(
                                "/v2/lm/document-grounding/pipelines/{pipelineId}/documents",
                                pipelineId
                        )
                        .header(
                                "AI-Resource-Group",
                                properties.getSapAiCore().getResourceGroup()
                        )
                        .headers(headers ->
                                headers.setBearerAuth(accessToken)
                        )
                        .retrieve()
                        .bodyToMono(String.class));
    }

    public Mono<String> getDataRepositories() {

        return tokenService.getAccessToken()
                .flatMap(accessToken -> webClient.get()
                        .uri("/v2/lm/document-grounding/retrieval/dataRepositories")
                        .header(
                                "AI-Resource-Group",
                                properties.getSapAiCore().getResourceGroup())
                        .headers(headers ->
                                headers.setBearerAuth(accessToken))
                        .retrieve()
                        .bodyToMono(String.class));
    }
}
