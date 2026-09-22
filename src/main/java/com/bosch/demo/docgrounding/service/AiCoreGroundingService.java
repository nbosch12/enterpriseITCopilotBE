package com.bosch.demo.docgrounding.service;

import java.util.LinkedHashMap;
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
    private final GroundingRepositoryService repositoryService;

    public AiCoreGroundingService(
            WebClient.Builder builder,
            AppProperties properties,
            AiCoreTokenService tokenService,
            GroundingRepositoryService repositoryService) {
        this.properties = properties;
        this.tokenService = tokenService;
        this.repositoryService = repositoryService;
        this.webClient = builder.baseUrl(properties.getSapAiCore().getApiUrl()).build();
    }

    /**
     * Create the one pipeline that feeds the single vector repository.
     *
     * <p>Uses {@link GroundingRepositoryService#includePath()}, which covers Docupedia and MongoDB
     * content together. Creating a pipeline per MongoDB query, as the per-query include paths
     * encouraged, is what produced a scatter of repositories in the first place.</p>
     */
    public Mono<String> createSinglePipeline() {
        return createS3Pipeline(repositoryService.includePath());
    }

    /**
     * What the backend believes the single repository setup is. Useful for confirming that the
     * configured repository id matches one that actually exists in SAP AI Core.
     */
    public Map<String, Object> repositoryConfiguration() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("repositoryId", repositoryService.configuredRepositoryId());
        info.put("enforceSingleRepository",
                properties.getGrounding() != null && properties.getGrounding().isEnforceSingleRepository());
        info.put("s3RootPrefix", repositoryService.rootPrefix());
        info.put("pipelineIncludePath", repositoryService.includePath());
        info.put("pipelineId", repositoryService.configuredPipelineId());
        info.put("s3Bucket", properties.getS3().getBucket());
        return info;
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
