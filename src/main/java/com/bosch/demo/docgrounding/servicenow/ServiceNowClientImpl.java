package com.bosch.demo.docgrounding.servicenow;

import com.bosch.demo.docgrounding.config.ServiceNowProperties;
import com.bosch.demo.docgrounding.servicenow.model.ServiceNowTicket;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
public class ServiceNowClientImpl implements ServiceNowClient {

    private static final Logger log = LoggerFactory.getLogger(ServiceNowClientImpl.class);
    private static final DateTimeFormatter SN_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WebClient            webClient;
    private final ServiceNowProperties props;
    private final ObjectMapper         objectMapper;

    public ServiceNowClientImpl(WebClient webClient,
                                 ServiceNowProperties props,
                                 ObjectMapper objectMapper) {
        this.webClient    = webClient;
        this.props        = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ServiceNowTicket> fetchNewTickets() {
        String url = String.format(
                "%s/api/now/table/incident"
                + "?sysparm_query=state=1^correlation_idISEMPTY^ORDERBYDESCsys_created_on"
                + "&sysparm_fields=sys_id,number,short_description,description,category,priority,caller_id,sys_created_on"
                + "&sysparm_display_value=true"
                + "&sysparm_limit=%d",
                props.getBaseUrl(), props.getFetchLimit());

        try {
            String body = webClient.get()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, basicAuth())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode result  = objectMapper.readTree(body).path("result");
            List<ServiceNowTicket> tickets = new ArrayList<>();
            for (JsonNode node : result) tickets.add(mapToTicket(node));
            log.info("Fetched {} new ticket(s) from ServiceNow.", tickets.size());
            return tickets;

        } catch (Exception e) {
            log.error("Failed to fetch tickets from ServiceNow: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public void writeBackJiraKey(String sysId, String jiraKey) {
        String url = String.format("%s/api/now/table/incident/%s", props.getBaseUrl(), sysId);
        try {
            webClient.patch()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, basicAuth())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("correlation_id", jiraKey))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            log.info("ServiceNow write-back: sysId={} updated with Jira key={}.", sysId, jiraKey);
        } catch (Exception e) {
            log.error("Failed to write Jira key back to ServiceNow {}: {}", sysId, e.getMessage(), e);
        }
    }

    private ServiceNowTicket mapToTicket(JsonNode node) {
        return new ServiceNowTicket(
                node.path("number").asText(),
                node.path("sys_id").asText(),
                node.path("short_description").asText(),
                node.path("description").asText(),
                node.path("category").asText("General"),
                mapPriority(node.path("priority").asText("3")),
                node.path("caller_id").asText("Unknown"),
                parseDate(node.path("sys_created_on").asText()));
    }

    private String mapPriority(String p) {
        return switch (p.trim()) {
            case "1" -> "CRITICAL";
            case "2" -> "HIGH";
            case "4", "5" -> "LOW";
            default  -> "MEDIUM";
        };
    }

    private LocalDateTime parseDate(String raw) {
        try { return LocalDateTime.parse(raw, SN_DATE_FORMAT); }
        catch (Exception e) { return LocalDateTime.now(); }
    }

    private String basicAuth() {
        String encoded = Base64.getEncoder().encodeToString(
                (props.getUsername() + ":" + props.getPassword()).getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }
}
