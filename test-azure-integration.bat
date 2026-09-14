@echo off
REM Azure Spring Apps Log Integration - Test Commands (Windows)
REM Run these commands to test the Azure Monitor integration

setlocal enabledelayedexpansion

set BASE_URL=http://localhost:8055

echo.
echo ================================================
echo Azure Spring Apps Log Integration - Test Suite
echo ================================================
echo.

REM Test 1: Health Check
echo 1. Testing Azure Integration Health
echo    Command: GET /api/ops/health/azure
echo    ---
curl -s -X GET "%BASE_URL%/api/ops/health/azure"
echo.
echo.

REM Test 2: Get all exceptions today (default limit: 10)
echo 2. Get All Exceptions Today ^(Latest 10^)
echo    Command: GET /api/ops/exceptions/today
echo    ---
curl -s -X GET "%BASE_URL%/api/ops/exceptions/today"
echo.
echo.

REM Test 3: Get exceptions from specific app
echo 3. Get Exceptions from Specific App ^(e.g., 'my-payment-app'^)
echo    Command: GET /api/ops/exceptions/today?appName=my-payment-app
echo    ---
curl -s -X GET "%BASE_URL%/api/ops/exceptions/today?appName=my-payment-app"
echo.
echo.

REM Test 4: Get top 20 exceptions
echo 4. Get Top 20 Exceptions Today
echo    Command: GET /api/ops/exceptions/today?limit=20
echo    ---
curl -s -X GET "%BASE_URL%/api/ops/exceptions/today?limit=20"
echo.
echo.

REM Test 5: Get exceptions from specific app with custom limit
echo 5. Get Exceptions ^(App: 'app-service', Limit: 50^)
echo    Command: GET /api/ops/exceptions/today?appName=app-service^&limit=50
echo    ---
curl -s -X GET "%BASE_URL%/api/ops/exceptions/today?appName=app-service&limit=50"
echo.
echo.

REM Test 6: Count by app
echo 6. Count by App
echo    Command: GET /api/ops/exceptions/today
echo    Response will show countByApp field
echo    ---
curl -s -X GET "%BASE_URL%/api/ops/exceptions/today" | findstr "countByApp"
echo.
echo.

echo ================================================
echo Test Summary
echo ================================================
echo.
echo If all endpoints return valid JSON with 200 OK status:
echo [OK] Azure integration is working correctly
echo.
echo If you see error responses:
echo [FAIL] Check AZURE_SETUP_QUICK.md for troubleshooting
echo.
echo Response Fields Explained:
echo   - totalCount: Total exceptions found today
echo   - countByApp: Number of exceptions per application
echo   - latestExceptions: Array of most recent exceptions
echo   - queryPeriod: Time period queried ^(e.g., 'Today'^)
echo   - hasErrors: Whether query execution had errors
echo   - errorMessage: Error details if hasErrors is true
echo.
echo Press any key to continue...
pause > nul

