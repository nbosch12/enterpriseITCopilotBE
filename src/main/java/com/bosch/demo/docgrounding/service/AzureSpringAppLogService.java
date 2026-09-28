package com.bosch.demo.docgrounding.service;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.credential.TokenCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.monitor.query.LogsQueryAsyncClient;
import com.azure.monitor.query.LogsQueryClientBuilder;
import com.azure.monitor.query.models.LogsQueryResult;
import com.azure.monitor.query.models.LogsTable;
import com.azure.monitor.query.models.LogsTableCell;
import com.azure.monitor.query.models.LogsTableRow;
import com.bosch.demo.docgrounding.config.AppProperties;
import com.bosch.demo.docgrounding.model.ExceptionLogEntry;
import com.bosch.demo.docgrounding.model.LogQueryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.exception.ExceptionUtils.getRootCauseMessage;

@Slf4j
@Service
public class AzureSpringAppLogService {

    private final AppProperties appProperties;
    private LogsQueryAsyncClient logsQueryClient;

    public AzureSpringAppLogService(AppProperties appProperties) {
        this.appProperties = appProperties;
        if (appProperties.getAzureMonitor().isEnabled()) {
            initializeLogsQueryClient();
        }
    }

    /**
     * Initialize Azure Logs Query Client with proper authentication
     */
    private void initializeLogsQueryClient() {
        try {
            TokenCredential credential;
            AppProperties.AzureMonitor azureConfig = appProperties.getAzureMonitor();

            if (azureConfig.getClientId() != null && azureConfig.getClientSecret() != null) {
                // Use service principal authentication
                credential = new ClientSecretCredentialBuilder()
                        .tenantId(azureConfig.getTenantId())
                        .clientId(azureConfig.getClientId())
                        .clientSecret(azureConfig.getClientSecret())
                        .build();
                log.info("Azure Logs Query Client initialized with service principal");
            } else {
                // Use managed identity (when running in Azure)
                credential = new DefaultAzureCredentialBuilder().build();
                log.info("Azure Logs Query Client initialized with managed identity");
            }

            logsQueryClient = new LogsQueryClientBuilder()
                    .credential(credential)
                    .buildAsyncClient();
        } catch (Exception e) {
            log.error("Failed to initialize Azure Logs Query Client", e);
            logsQueryClient = null;
        }
    }

    /**
     * Query exceptions from Azure Spring Apps for today
     *
     * @param appName optional filter for specific app
     * @param limit   number of latest exceptions to return
     * @return Mono with log query result
     */
    public Mono<LogQueryResult> getAppLogs(String appName, int limit, String keywordToSearch, String timeDuration) {
        if (!appProperties.getAzureMonitor().isEnabled()) {
            return Mono.just(createDisabledResult());
        }

        if (logsQueryClient == null) {
            return Mono.just(
                    createErrorResult("Azure Logs Query Client not initialized")
            );
        }

        String workspaceId =
                appProperties.getAzureMonitor().getWorkspaceId();

        if (workspaceId == null || workspaceId.isBlank()) {
            return Mono.just(
                    createErrorResult("Azure Log Analytics workspace ID is missing")
            );
        }

        int safeLimit = Math.max(1, Math.min(limit, 100));
        String safeDuration = sanitizeTimeDuration(timeDuration);
        String query = buildBusinessQuery(appName, keywordToSearch, safeDuration);

        log.info(
                "Executing Azure Monitor exception query. App: {}, Limit: {}, TimeDuration: {}",
                appName,
                safeLimit,
                safeDuration
        );

        return logsQueryClient
                .queryWorkspace(workspaceId, query, null)
                .map(result -> parseLogsQueryResult(result, safeLimit, safeDuration))
                .switchIfEmpty(
                        Mono.fromSupplier(() ->
                                createErrorResult(
                                        "Azure Monitor returned an empty response"
                                )
                        )
                )
                .onErrorResume(ex -> {
                    log.error("Error querying Azure logs", ex);

                    if (ex instanceof HttpResponseException httpResponseException
                            && httpResponseException.getResponse() != null
                            && httpResponseException.getResponse().getStatusCode() == 403) {
                        return Mono.just(
                                createErrorResult(
                                        "Azure Monitor access denied (403). Grant 'Log Analytics Reader' "
                                                + "or 'Monitoring Reader' on the configured Log Analytics workspace "
                                                + "to the Azure identity used by this application."
                                )
                        );
                    }

                    if (ex instanceof HttpResponseException httpResponseException
                            && httpResponseException.getResponse() != null
                            && httpResponseException.getResponse().getStatusCode() == 400) {
                        return Mono.just(
                                createErrorResult(
                                        "Azure Monitor query syntax error (400). Check the generated Kusto query or simplify filters. Root cause: "
                                                + getRootCauseMessage(ex)
                                )
                        );
                    }

                    if (isNetworkError(ex)) {
                        log.warn(
                                "Network error connecting to Azure Log Analytics. "
                                        + "Check DNS, firewall, proxy, or VPN."
                        );
                    }

                    return Mono.just(
                            createErrorResult(
                                    "Failed to query Azure logs: "
                                            + getRootCauseMessage(ex)
                            )
                    );
                });
    }

    /**
     * Build query to fetch logs from Azure Monitor for today, optionally filtered by app name and keyword.
     */
    private String buildBusinessQuery(String appName, String keywordToSearch, String safeDuration) {

        StringBuilder query = new StringBuilder(
                "union AppTraces, AppExceptions\n" +
                        "| where TimeGenerated >= ago(" + safeDuration + ")\n"
        );

        if (keywordToSearch != null && !keywordToSearch.isBlank()) {
            query.append("| where SeverityLevel >= 1\n")
                    .append("| where Message has \"")
                    .append(keywordToSearch.replace("\"", "\\\""))
                    .append("\"\n");
        } else {
            query.append("| where SeverityLevel >= 1\n");
        }

        if (appName != null && !appName.isBlank()) {
            query.append("| where AppRoleName == \"")
                    .append(appName.replace("\"", "\\\""))
                    .append("\"\n");
        }

        query.append("| project TimeGenerated, AppRoleName, SeverityLevel, Message, OperationName\n")
                .append("| order by TimeGenerated desc");

        return query.toString();
    }

    private String sanitizeTimeDuration(String timeDuration) {
        if (timeDuration == null || timeDuration.isBlank()) {
            return "6h";
        }

        String value = timeDuration.trim().toLowerCase();

        // Allow only simple Kusto durations like: 15m, 6h, 1d, 2w
        if (value.matches("^\\d+[mhdw]$")) {
            return value;
        }

        log.warn("Invalid timeDuration '{}', falling back to 6h", timeDuration);
        return "6h";
    }
/*

    private String buildExceptionsQuery(String appName) {
        StringBuilder query = new StringBuilder();
        query.append("let appFilter = '")
                .append(escapeKustoString(appName))
                .append("';\n")
                .append("union isfuzzy=true\n")
                .append("(\n")
                .append("    AppExceptions\n")
                .append("    | extend SourceTable = 'AppExceptions'\n")
                .append("    | extend LogMessage = tostring(Message)\n")
                .append("    | extend OperationName = coalesce(tostring(OperationName), tostring(Method))\n")
                .append("    | extend StatusCode = tostring(ProblemId)\n")
                .append("),\n")
                .append("(\n")
                .append("    AppTraces\n")
                .append("    | extend SourceTable = 'AppTraces'\n")
                .append("    | extend LogMessage = tostring(Message)\n")
                .append("    | extend OperationName = tostring(OperationName)\n")
                .append("    | extend StatusCode = ''\n")
                .append("),\n")
                .append("(\n")
                .append("    AppRequests\n")
                .append("    | extend SourceTable = 'AppRequests'\n")
                .append("    | extend LogMessage = coalesce(tostring(ResultCode), tostring(Name), tostring(Url), '')\n")
                .append("    | extend OperationName = tostring(Name)\n")
                .append("    | extend StatusCode = tostring(ResultCode)\n")
                .append(")\n")
                .append("| where TimeGenerated >= ago(4h)\n")
                .append("| extend SearchableAppName = coalesce(tostring(AppRoleName), '')\n")
                .append("| where isempty(appFilter) or SearchableAppName contains appFilter or LogMessage contains appFilter or OperationName contains appFilter\n")
                .append("| where SeverityLevel >= 3\n")
                .append("    or LogMessage contains_cs 'ERROR'\n")
                .append("    or LogMessage contains 'Exception'\n")
                .append("    or LogMessage contains 'exception'\n")
                .append("    or LogMessage contains 'Caused by'\n")
                .append("    or LogMessage contains 'Stacktrace'\n")
                .append("    or LogMessage contains 'Partial success'\n")
                .append("    or LogMessage contains 'Duplicate Key'\n")
                .append("    or LogMessage contains 'CONFLICT'\n")
                .append("    or StatusCode startswith '4'\n")
                .append("    or StatusCode startswith '5'\n")
                .append("| project TimeGenerated, AppRoleName = SearchableAppName, SeverityLevel = toint(coalesce(SeverityLevel, iif(SourceTable == 'AppRequests', 3, 0))), LogMessage, OperationName, SourceTable, StatusCode\n")
                .append("| order by TimeGenerated desc");

        return query.toString();
    }
*/

    /*private String buildAppExceptionsQuery(String appName) {
        String baseQuery = "AppExceptions\n" +
                "| where TimeGenerated >= startofday(now())\n";

        if (appName != null && !appName.isBlank()) {
            baseQuery += "| where AppRoleName == \"" + appName + "\"\n";
        }

        baseQuery += "| project TimeGenerated, AppRoleName, ExceptionType, Message, ProblemId\n" +
                "| order by TimeGenerated desc";

        return baseQuery;
    }*/

    /**
     * Parse Azure Logs Query result and map to our model
     */
    private LogQueryResult parseLogsQueryResult(LogsQueryResult result, int limit, String safeDuration) {
        LogQueryResult response = new LogQueryResult();
        response.setQueryPeriod("Last " + safeDuration);
        response.setHasErrors(false);

        if (result == null) {
            response.setTotalCount(0);
            response.setCountByApp(new HashMap<>());
            response.setLatestExceptions(new ArrayList<>());
            return response;
        }

        List<ExceptionLogEntry> exceptions = new ArrayList<>();
        Map<String, Long> countByApp = new HashMap<>();

        try {
            // Get the first table from results
            if (result.getAllTables().isEmpty()) {
                response.setTotalCount(0);
                response.setCountByApp(new HashMap<>());
                response.setLatestExceptions(new ArrayList<>());
                return response;
            }

            LogsTable table = result.getAllTables().getFirst();

            for (var row : table.getRows()) {
                try {
                    // Get column values by name (from Kusto query projection)
                    String timeGenObj = getColumnValueAsString(row,"TimeGenerated");
                    String appRoleObj = getColumnValueAsString(row,"AppRoleName");
                    String severityObj = getColumnValueAsString(row,"SeverityLevel");
                    String messageObj = getColumnValueAsString(row,"Message");
                    String operationObj = getColumnValueAsString(row,"OperationName");

                    LocalDateTime timeGenerated = toLocalDateTime(timeGenObj);
                    String appRoleName = appRoleObj != null ? appRoleObj : "Unknown";
                    int severityLevel = parseSeverity(severityObj);
                    String message = messageObj != null ? messageObj.toString() : "";
                    String operationName = operationObj != null ? operationObj.toString() : "";

                    ExceptionLogEntry entry = new ExceptionLogEntry(
                            timeGenerated,
                            appRoleName,
                            severityLevel,
                            message,
                            operationName
                    );

                    exceptions.add(entry);
                    countByApp.merge(appRoleName, 1L, Long::sum);

                } catch (Exception e) {
                    log.warn("Failed to parse log row", e);
                }
            }

            // Get the latest N exceptions
            List<ExceptionLogEntry> latest = exceptions.stream()
                    .limit(limit)
                    .collect(Collectors.toList());

            response.setTotalCount(exceptions.size());
            response.setCountByApp(countByApp);
            response.setLatestExceptions(latest);

            log.info("Found {} exceptions today, {} by app: {}",
                    response.getTotalCount(),
                    response.getCountByApp().size(),
                    response.getCountByApp());

        } catch (Exception e) {
            log.error("Error parsing logs query result", e);
            response.setHasErrors(true);
            response.setErrorMessage("Error parsing results: " + e.getMessage());
        }

        return response;
    }

    private String getColumnValueAsString(
            LogsTableRow row,
            String columnName
    ) {
        return row.getColumnValue(columnName)
                .map(LogsTableCell::getValueAsString)
                .orElse(null);
    }

    /**
     * Create error result when feature is disabled
     */
    private LogQueryResult createDisabledResult() {
        LogQueryResult result = new LogQueryResult();
        result.setHasErrors(true);
        result.setErrorMessage("Azure Monitor log integration is disabled");
        result.setTotalCount(0);
        result.setCountByApp(new HashMap<>());
        result.setLatestExceptions(new ArrayList<>());
        return result;
    }

    /**
     * Create error result with custom message
     */
    private LogQueryResult createErrorResult(String message) {
        LogQueryResult result = new LogQueryResult();
        result.setHasErrors(true);
        result.setErrorMessage(message);
        result.setTotalCount(0);
        result.setCountByApp(new HashMap<>());
        result.setLatestExceptions(new ArrayList<>());
        return result;
    }
    
    private LocalDateTime toLocalDateTime(Object timeGenObj) {
        if (timeGenObj == null) {
            return LocalDateTime.now(ZoneOffset.UTC);
        }

        // Handle Azure LogsTableCell wrapped in Optional
        String strValue = timeGenObj.toString().trim();
        if (strValue.startsWith("Optional[") && strValue.endsWith("]")) {
            return LocalDateTime.now(ZoneOffset.UTC);
        }

        if (timeGenObj instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toLocalDateTime();
        }

        if (timeGenObj instanceof String stringValue) {
            try {
                return OffsetDateTime.parse(stringValue, DateTimeFormatter.ISO_DATE_TIME).toLocalDateTime();
            } catch (Exception ex1) {
                try {
                    return LocalDateTime.parse(stringValue, DateTimeFormatter.ISO_DATE_TIME);
                } catch (Exception ex2) {
                    log.debug("Failed to parse datetime '{}': {}", stringValue, ex2.getMessage());
                    return LocalDateTime.now(ZoneOffset.UTC);
                }
            }
        }

        try {
            return OffsetDateTime.parse(strValue, DateTimeFormatter.ISO_DATE_TIME).toLocalDateTime();
        } catch (Exception ex1) {
            try {
                return LocalDateTime.parse(strValue, DateTimeFormatter.ISO_DATE_TIME);
            } catch (Exception ex2) {
                log.debug("Failed to parse datetime object '{}': {}", strValue, ex2.getMessage());
                return LocalDateTime.now(ZoneOffset.UTC);
            }
        }
    }

    private int parseSeverity(Object severityObj) {
        if (severityObj == null) {
            return 0;
        }
        if (severityObj instanceof Number number) {
            return number.intValue();
        }
        String value = severityObj.toString();
        if (Objects.equals(value, "null") || value.isBlank()) {
            return 0;
        }
        return Integer.parseInt(value);
    }

    /**
     * Check if exception is a network-related error
     */
    private boolean isNetworkError(Throwable ex) {
        String message = ex.getMessage();
        if (message == null) {
            message = ex.getClass().getSimpleName();
        }

        return ex instanceof java.net.UnknownHostException ||
               ex instanceof java.net.ConnectException ||
               ex instanceof java.net.SocketException ||
               ex instanceof java.net.NoRouteToHostException ||
               message.contains("api.loganalytics.io") ||
               message.contains("Connection refused") ||
               message.contains("Connection timeout") ||
               message.contains("DNS");
    }
}

