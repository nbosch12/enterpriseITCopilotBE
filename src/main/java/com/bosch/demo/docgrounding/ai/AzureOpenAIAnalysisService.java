package com.bosch.demo.docgrounding.ai;

import com.bosch.demo.docgrounding.ai.model.JiraTicketPayload;
import com.bosch.demo.docgrounding.config.AzureOpenAIProperties;
import com.bosch.demo.docgrounding.servicenow.model.ServiceNowTicket;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
public class AzureOpenAIAnalysisService implements AIAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AzureOpenAIAnalysisService.class);

    private final WebClient             webClient;
    private final AzureOpenAIProperties props;
    private final ObjectMapper          objectMapper;

    public AzureOpenAIAnalysisService(WebClient webClient,
                                       AzureOpenAIProperties props,
                                       ObjectMapper objectMapper) {
        this.webClient    = webClient;
        this.props        = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public JiraTicketPayload analyze(ServiceNowTicket ticket) {
        try {
            return callAzureOpenAI(ticket);
        } catch (Exception e) {
            log.warn("Azure OpenAI call failed for ticket {} — using rule-based fallback. Reason: {}",
                    ticket.id(), e.getMessage());
            return ruleBasedFallback(ticket);
        }
    }

    private JiraTicketPayload callAzureOpenAI(ServiceNowTicket ticket) {
        String systemPrompt = """
                You are an IT operations assistant that converts ServiceNow tickets into Jira issue payloads.
                Respond ONLY with a valid JSON object (no markdown, no explanation) with exactly these four fields:
                {"summary":"<concise one-line title, max 255 chars>","description":"<full issue description>","issueType":"<Bug|Task|Story|Improvement>","priority":"<CRITICAL|HIGH|MEDIUM|LOW>"}
                """;

        String userPrompt = String.format(
                "Convert this ServiceNow ticket to a Jira issue:\nID: %s\nTitle: %s\nDescription: %s\nCategory: %s\nPriority: %s\nRequested By: %s",
                ticket.id(), ticket.title(), ticket.description(),
                ticket.category(), ticket.priority(), ticket.requestedBy());

        Map<String, Object> requestBody = Map.of(
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user",   "content", userPrompt)),
                "max_tokens",  500,
                "temperature", 0.2);

        String url = props.getEndpoint()
                + "/openai/deployments/" + props.getDeployment()
                + "/chat/completions?api-version=2024-02-01";

        String responseBody = webClient.post()
                .uri(url)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("api-key", props.getApiKey())
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        return parseResponse(responseBody, ticket);
    }

    private JiraTicketPayload parseResponse(String body, ServiceNowTicket ticket) {
        try {
            JsonNode root    = objectMapper.readTree(body);
            String   content = root.path("choices").get(0)
                                   .path("message").path("content").asText();
            content = content.replaceAll("```json", "").replaceAll("```", "").trim();
            JsonNode parsed  = objectMapper.readTree(content);
            return new JiraTicketPayload(
                    truncate(parsed.path("summary").asText(ticket.title()), 255),
                    parsed.path("description").asText(ticket.description()),
                    parsed.path("issueType").asText("Task"),
                    parsed.path("priority").asText("MEDIUM"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Azure OpenAI response: " + e.getMessage(), e);
        }
    }

    private JiraTicketPayload ruleBasedFallback(ServiceNowTicket ticket) {
        String issueType = switch (ticket.category().toLowerCase()) {
            case "hardware" -> "Bug";
            default         -> "Task";
        };
        return new JiraTicketPayload(
                truncate(ticket.title(), 255),
                ticket.description(),
                issueType,
                ticket.priority());
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return "";
        return value.length() > maxLen ? value.substring(0, maxLen - 3) + "..." : value;
    }
}
