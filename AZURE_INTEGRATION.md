# Azure Spring Apps Log Integration

This document describes how to integrate Azure Monitor logs from your Azure Spring Apps subscription to query exceptions and application logs in real-time.

## Overview

The Azure integration feature allows you to:
- Query exceptions from all Azure Spring Apps for today
- Filter by specific app name
- Get exception counts grouped by application
- Retrieve the latest exceptions with details (timestamp, severity, message)

## Architecture

The integration consists of:

1. **AzureSpringAppLogService** - Core service that queries Azure Log Analytics
2. **OpsLogController** - REST endpoints for accessing log data
3. **LogQueryResult** & **ExceptionLogEntry** - Data models for responses
4. **AppProperties** - Configuration properties for Azure Monitor

## Setup Instructions

### Prerequisites

You need:
1. An Azure subscription with Azure Spring Apps instances
2. Log Analytics Workspace (where Azure Spring Apps logs are sent)
3. Service Principal or Managed Identity with "Log Analytics Reader" role

### Step 1: Enable Diagnostic Settings

In Azure Portal, for each Azure Spring Apps instance:

1. Go to **Azure Spring Apps** → Your instance
2. Navigate to **Diagnostic settings** (under Monitoring)
3. Click **Add diagnostic setting**
4. Enable logs: `ApplicationConsole`, `AppTracking`, `AppEvents`
5. Send to **Log Analytics Workspace**

### Step 2: Get Required Azure Values

From Azure Portal:

1. **Workspace ID**: Log Analytics Workspace → Properties → **Workspace ID**
2. **Tenant ID**: Azure Active Directory → Overview → **Tenant ID**
3. **Client ID & Secret** (Service Principal):
   - Create Service Principal: `az ad sp create-for-rbac --name "docupedia-logs"`
   - Copy the `appId` (Client ID) and `password` (Client Secret)
   - Assign "Log Analytics Reader" role to this service principal

**Alternative**: Use **Managed Identity** (if running in Azure):
- Skip client ID/secret if deployed in Azure Spring Cloud or Azure App Service
- The application will use Managed Identity automatically

### Step 3: Configure Application

Edit `src/main/resources/application.yml`:

```yaml
app:
  azure-monitor:
    enabled: true
    workspace-id: ${AZURE_LOG_ANALYTICS_WORKSPACE_ID}
    tenant-id: ${AZURE_TENANT_ID}
    client-id: ${AZURE_CLIENT_ID}
    client-secret: ${AZURE_CLIENT_SECRET}
```

Or set environment variables:

```bash
export AZURE_MONITOR_ENABLED=true
export AZURE_LOG_ANALYTICS_WORKSPACE_ID=your-workspace-id
export AZURE_TENANT_ID=your-tenant-id
export AZURE_CLIENT_ID=your-client-id
export AZURE_CLIENT_SECRET=your-client-secret
```

### Step 4: Run the Application

```bash
mvn clean install
java -jar target/docupedia-grounding-btp-0.0.1-SNAPSHOT.jar
```

## API Endpoints

### Get Exceptions Today

**GET** `/api/ops/exceptions/today`

**Query Parameters:**
- `appName` (optional): Filter by specific app name
- `limit` (optional, default: 10): Maximum number of exceptions to return

**Example:**
```bash
# Get all exceptions today (latest 10)
curl http://localhost:8055/api/ops/exceptions/today

# Get exceptions from specific app (latest 20)
curl "http://localhost:8055/api/ops/exceptions/today?appName=my-app&limit=20"
```

**Response:**
```json
{
  "totalCount": 45,
  "countByApp": {
    "app-payment-service": 12,
    "app-user-service": 23,
    "app-order-service": 10
  },
  "latestExceptions": [
    {
      "timeGenerated": "2026-09-11T10:45:30",
      "appRoleName": "app-payment-service",
      "severityLevel": 3,
      "message": "NullPointerException: Cannot process transaction",
      "operationName": "ProcessPayment"
    },
    {
      "timeGenerated": "2026-09-11T10:40:15",
      "appRoleName": "app-user-service",
      "severityLevel": 4,
      "message": "DatabaseException: Connection timeout",
      "operationName": "UpdateUserProfile"
    }
  ],
  "queryPeriod": "Today",
  "hasErrors": false,
  "errorMessage": null
}
```

### Health Check

**GET** `/api/ops/health/azure`

Verifies Azure integration is active.

**Response:**
```
Azure Spring Apps log integration is active
```

## Kusto Query Reference

The service uses the following Kusto query to fetch exceptions:

```kusto
AppTraces
| where TimeGenerated >= startofday(now())
| where SeverityLevel >= 3 or Message has "Exception"
| project TimeGenerated, AppRoleName, SeverityLevel, Message, OperationName
| order by TimeGenerated desc
```

### Query Tables

The integration queries the following Log Analytics tables:
- **AppTraces** - Application trace/console logs
- **AppEvents** - Application events
- **AzureDiagnostics** - Azure resource diagnostics (if configured)

**Note**: Table names and schema may differ based on your Azure Spring Apps configuration.

## Troubleshooting

### "Azure Monitor log integration is disabled"
- Ensure `AZURE_MONITOR_ENABLED=true` in configuration

### "Failed to query Azure logs: Authentication failed"
- Verify Service Principal credentials
- Ensure the service principal has "Log Analytics Reader" role on the workspace
- Check tenant ID is correct

### "No results returned"
- Verify diagnostic settings are enabled on Azure Spring Apps
- Check that logs are being sent to Log Analytics (query manually in Portal)
- Verify the time range (query looks at "today" - startofday)

### "Connection timeout"
- Check network connectivity to Azure
- Verify firewall rules allow outbound connections
- Check service is running and can reach Log Analytics endpoint

## Advanced Configuration

### Custom Query

To use a custom Kusto query, extend `AzureSpringAppLogService.buildExceptionsQuery()`:

```java
private String buildExceptionsQuery(String appName, String customFilter) {
    String query = "AppTraces\n" +
        "| where TimeGenerated >= startofday(now())\n" +
        customFilter + "\n" +
        "| order by TimeGenerated desc";
    return query;
}
```

### Caching Results

To reduce Azure calls, add caching with Spring:

```java
@Cacheable(value = "exceptionCache", unless = "#result.hasErrors")
public Mono<LogQueryResult> getExceptionsToday(String appName, int limit) {
    // ...
}
```

## Security Considerations

1. **Store credentials securely**: Use Azure Key Vault or environment variables, not config files
2. **Least privilege**: Give service principal only "Log Analytics Reader" role
3. **Network**: Run behind VPN/firewall in production
4. **Audit**: Enable audit logging in Log Analytics
5. **Rotation**: Rotate service principal credentials periodically

## Performance Notes

- Query execution typically takes 2-5 seconds
- Results are not cached by default; disable cache for real-time updates
- For high-frequency queries, consider implementing rate limiting
- Azure charges for Log Analytics queries (though modest at this scale)

## Support & Documentation

- Azure SDK: https://github.com/Azure/azure-sdk-for-java
- Log Analytics Query: https://learn.microsoft.com/en-us/azure/data-explorer/kusto/query/
- Azure Monitor: https://learn.microsoft.com/en-us/azure/azure-monitor/

---

**Last Updated**: September 2026

