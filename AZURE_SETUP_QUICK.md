# Azure Integration - Quick Start Guide

## 1. Enable in Application

Open `src/main/resources/application.yml` and set:

```yaml
app:
  azure-monitor:
    enabled: true
    workspace-id: <your-workspace-id>
    tenant-id: <your-tenant-id>
    client-id: <your-client-id>
    client-secret: <your-client-secret>
```

Or via environment variables:
```bash
AZURE_MONITOR_ENABLED=true
AZURE_LOG_ANALYTICS_WORKSPACE_ID=<workspace-id>
AZURE_TENANT_ID=<tenant-id>
AZURE_CLIENT_ID=<client-id>
AZURE_CLIENT_SECRET=<client-secret>
```

## 2. Get Azure Credentials

### Option A: Using Service Principal (Recommended)

```bash
# Create service principal
az ad sp create-for-rbac --name "docupedia-logs"

# Output will show:
# "appId": <CLIENT_ID>
# "password": <CLIENT_SECRET>
# "tenant": <TENANT_ID>

# Get Workspace ID
az monitor log-analytics workspace show \
  --resource-group <resource-group> \
  --workspace-name <workspace-name> \
  --query id -o tsv
```

### Option B: Using Managed Identity (Azure-hosted only)

If running in Azure App Service or Azure Spring Cloud:
- Skip client ID/secret configuration
- Application will use managed identity automatically
- Ensure managed identity has "Log Analytics Reader" role

### Get Workspace ID from Portal

1. Log Analytics → Your Workspace
2. Click **Properties**
3. Copy **Workspace ID**

## 3. Assign Permissions

Grant "Log Analytics Reader" role to your service principal:

```bash
az role assignment create \
  --assignee <CLIENT_ID> \
  --role "Log Analytics Reader" \
  --scope /subscriptions/<subscription-id>/resourcegroups/<resource-group>/providers/microsoft.operationalinsights/workspaces/<workspace-name>
```

## 4. Test the Integration

### Start the application:
```bash
mvn clean spring-boot:run
```

### Call the API:
```bash
curl http://localhost:8055/api/ops/exceptions/today
```

### Expected response (if Azure configured correctly):
```json
{
  "totalCount": 5,
  "countByApp": {
    "app-service-1": 3,
    "app-service-2": 2
  },
  "latestExceptions": [
    {
      "timeGenerated": "2026-09-11T10:45:30",
      "appRoleName": "app-service-1",
      "severityLevel": 3,
      "message": "NullPointerException occurred",
      "operationName": "ProcessRequest"
    }
  ],
  "queryPeriod": "Today",
  "hasErrors": false
}
```

## 5. Verify Azure Spring Apps Configuration

In Azure Portal:

1. Azure Spring Apps → Your Instance
2. **Diagnostic settings** (under Monitoring)
3. Verify logs are sent to Log Analytics:
   - `ApplicationConsole` ✓
   - `AppTracking` ✓
   - `AppEvents` ✓
   - Send to: Log Analytics Workspace ✓

## 6. Common Issues

| Issue | Solution |
|-------|----------|
| "Authentication failed" | Verify client ID/secret are correct |
| "No results returned" | Check diagnostic settings in Azure Portal |
| "Connection timeout" | Verify firewall allows outbound HTTPS to Azure |
| "Disabled" | Set `AZURE_MONITOR_ENABLED=true` |

## 7. API Examples

### Get all exceptions from today
```bash
curl http://localhost:8055/api/ops/exceptions/today
```

### Get exceptions from specific app
```bash
curl "http://localhost:8055/api/ops/exceptions/today?appName=my-app"
```

### Get top 50 exceptions
```bash
curl "http://localhost:8055/api/ops/exceptions/today?limit=50"
```

### Check Azure health
```bash
curl http://localhost:8055/api/ops/health/azure
```

## 8. Deploy to Azure Spring Cloud

Set environment variables in Azure Portal:

1. Azure Spring Cloud → Your App → Configuration
2. Add Environment Variables:
   - `AZURE_MONITOR_ENABLED` = `true`
   - `AZURE_LOG_ANALYTICS_WORKSPACE_ID` = `<workspace-id>`
   - `AZURE_TENANT_ID` = `<tenant-id>`
   - `AZURE_CLIENT_ID` = `<client-id>`
   - `AZURE_CLIENT_SECRET` = `<secret>`

Or use **System-Assigned Managed Identity** (recommended):
- Enable in App Configuration
- Set `AZURE_MONITOR_ENABLED=true` only
- Skip credentials (uses managed identity)

## Next Steps

- Read full documentation: [AZURE_INTEGRATION.md](AZURE_INTEGRATION.md)
- Run integration tests: `mvn test -Dtest=AzureSpringAppLogServiceIT`
- Monitor application logs: `az spring-cloud app log stream --name <app-name>`

---

**Time to setup**: ~10 minutes  
**Testing time**: ~2 minutes

