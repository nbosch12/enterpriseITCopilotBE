package com.bosch.demo.docgrounding.jira;

import com.bosch.demo.docgrounding.ai.model.JiraTicketPayload;
import com.bosch.demo.docgrounding.config.JiraProperties;
import com.bosch.demo.docgrounding.jira.model.JiraIssueRequest;
import com.bosch.demo.docgrounding.jira.model.JiraIssueResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class JiraClient {

    private static final Logger log             = LoggerFactory.getLogger(JiraClient.class);
    private static final String SN_LABEL_PREFIX = "SN-";
    private static final Map<String, String> PRIORITY_MAP = Map.of(
            "critical", "Critical",
            "high",     "Major",
            "medium",   "Minor",
            "low",      "Trivial",
            "blocker",  "Blocker");

    private final WebClient      webClient;
    private final JiraProperties props;
    private final ObjectMapper   objectMapper;

    public JiraClient(WebClient webClient, JiraProperties props, ObjectMapper objectMapper) {
        this.webClient    = webClient;
        this.props        = props;
        this.objectMapper = objectMapper;
    }

    public boolean validateToken() {
        try {
            return getWithRetry(apiUrl("/rest/api/2/myself")) != null;
        } catch (Exception e) {
            log.warn("Jira token validation failed: {}", e.getMessage());
            return false;
        }
    }

    public Set<String> findAlreadyProcessedIds(String projectKey) {
        Set<String> ids = new HashSet<>();
        try {
            String jql = "project=" + projectKey + " AND labels is not EMPTY";
            String url = apiUrl("/rest/api/2/search?jql="
                    + java.net.URLEncoder.encode(jql, StandardCharsets.UTF_8)
                    + "&fields=labels&maxResults=1000");
            String body = getWithRetry(url);
            if (body == null) return ids;
            for (JsonNode issue : objectMapper.readTree(body).path("issues")) {
                for (JsonNode label : issue.path("fields").path("labels")) {
                    String v = label.asText();
                    if (v.startsWith(SN_LABEL_PREFIX)) ids.add(v.substring(SN_LABEL_PREFIX.length()));
                }
            }
        } catch (Exception e) {
            log.warn("Could not load processed ticket IDs from Jira: {}", e.getMessage());
        }
        return ids;
    }

    public JiraIssueResponse createIssue(JiraTicketPayload payload, String projectKey, String snId) {
        JiraIssueRequest.User user = new JiraIssueRequest.User(props.getUsername());
        JiraIssueRequest request = new JiraIssueRequest(new JiraIssueRequest.Fields(
                new JiraIssueRequest.Project(projectKey),
                payload.summary(),
                new JiraIssueRequest.IssueType(resolveIssueType(payload.issueType())),
                new JiraIssueRequest.Priority(mapPriority(payload.priority())),
                payload.description(),
                user, user,
                List.of(SN_LABEL_PREFIX + snId)));
        try {
            String body = webClient.post()
                    .uri(URI.create(apiUrl("/rest/api/2/issue")))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getPat())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            JsonNode root = objectMapper.readTree(body);
            String key    = root.path("key").asText();
            return new JiraIssueResponse(key, root.path("id").asText(),
                    props.getBaseUrl() + "/browse/" + key);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Jira issue: " + e.getMessage(), e);
        }
    }

    public Optional<JsonNode> getIssue(String issueKey) {
        try {
            String body = webClient.get()
                    .uri(URI.create(apiUrl("/rest/api/2/issue/" + issueKey + "?fields=summary,status")))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getPat())
                    .retrieve()
                    .onStatus(s -> s == HttpStatus.NOT_FOUND, r -> Mono.empty())
                    .bodyToMono(String.class)
                    .retryWhen(retryOnIoError())
                    .block();
            if (body == null) return Optional.empty();
            return Optional.of(objectMapper.readTree(body));
        } catch (Exception e) {
            log.warn("Failed to fetch issue {}: {}", issueKey, e.getMessage());
            return Optional.empty();
        }
    }

    public List<String> getIssueTypes() {
        try {
            String body = getWithRetry(apiUrl("/rest/api/2/issuetype"));
            List<String> types = new ArrayList<>();
            for (JsonNode n : objectMapper.readTree(body))
                if (!n.path("subtask").asBoolean(false)) types.add(n.path("name").asText());
            return types;
        } catch (Exception e) {
            return List.of("Task", "Bug", "Story", "Improvement");
        }
    }

    private String getWithRetry(String url) {
        return webClient.get()
                .uri(URI.create(url))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getPat())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(retryOnIoError())
                .block();
    }

    private Retry retryOnIoError() {
        return Retry.fixedDelay(2, Duration.ofMillis(500))
                .filter(e -> e instanceof WebClientRequestException);
    }

    private String resolveIssueType(String requested) {
        List<String> available = getIssueTypes();
        for (String t : available) if (t.equalsIgnoreCase(requested)) return t;
        return available.isEmpty() ? requested : available.get(0);
    }

    private String mapPriority(String priority) {
        if (priority == null) return "Major";
        String mapped = PRIORITY_MAP.get(priority.toLowerCase());
        return mapped != null ? mapped : "Major";
    }

    private String apiUrl(String path) { return props.getBaseUrl() + path; }
}
