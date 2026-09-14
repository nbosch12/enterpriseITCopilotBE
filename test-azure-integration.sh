#!/bin/bash
# Azure Spring Apps Log Integration - Test Commands
# Run these commands to test the Azure Monitor integration

# Base URL
BASE_URL="http://localhost:8055"

echo "================================================"
echo "Azure Spring Apps Log Integration - Test Suite"
echo "================================================"
echo ""

# Test 1: Health Check
echo "1. Testing Azure Integration Health"
echo "   Command: GET /api/ops/health/azure"
echo "   ---"
curl -s -X GET "${BASE_URL}/api/ops/health/azure" | jq '.' 2>/dev/null || curl -s -X GET "${BASE_URL}/api/ops/health/azure"
echo ""
echo ""

# Test 2: Get all exceptions today (default limit: 10)
echo "2. Get All Exceptions Today (Latest 10)"
echo "   Command: GET /api/ops/exceptions/today"
echo "   ---"
curl -s -X GET "${BASE_URL}/api/ops/exceptions/today" | jq '.' 2>/dev/null || curl -s -X GET "${BASE_URL}/api/ops/exceptions/today"
echo ""
echo ""

# Test 3: Get exceptions from specific app
echo "3. Get Exceptions from Specific App (e.g., 'my-payment-app')"
echo "   Command: GET /api/ops/exceptions/today?appName=my-payment-app"
echo "   ---"
curl -s -X GET "${BASE_URL}/api/ops/exceptions/today?appName=my-payment-app" | jq '.' 2>/dev/null || curl -s -X GET "${BASE_URL}/api/ops/exceptions/today?appName=my-payment-app"
echo ""
echo ""

# Test 4: Get top 20 exceptions
echo "4. Get Top 20 Exceptions Today"
echo "   Command: GET /api/ops/exceptions/today?limit=20"
echo "   ---"
curl -s -X GET "${BASE_URL}/api/ops/exceptions/today?limit=20" | jq '.' 2>/dev/null || curl -s -X GET "${BASE_URL}/api/ops/exceptions/today?limit=20"
echo ""
echo ""

# Test 5: Get exceptions from specific app with custom limit
echo "5. Get Exceptions (App: 'app-service', Limit: 50)"
echo "   Command: GET /api/ops/exceptions/today?appName=app-service&limit=50"
echo "   ---"
curl -s -X GET "${BASE_URL}/api/ops/exceptions/today?appName=app-service&limit=50" | jq '.' 2>/dev/null || curl -s -X GET "${BASE_URL}/api/ops/exceptions/today?appName=app-service&limit=50"
echo ""
echo ""

# Test 6: Extract just the count by app
echo "6. Extract Count by App Only"
echo "   Command: GET /api/ops/exceptions/today | jq '.countByApp'"
echo "   ---"
curl -s -X GET "${BASE_URL}/api/ops/exceptions/today" | jq '.countByApp' 2>/dev/null
echo ""
echo ""

# Test 7: Extract just the latest exceptions
echo "7. Extract Latest Exceptions Only"
echo "   Command: GET /api/ops/exceptions/today | jq '.latestExceptions'"
echo "   ---"
curl -s -X GET "${BASE_URL}/api/ops/exceptions/today" | jq '.latestExceptions[0:3]' 2>/dev/null
echo ""
echo ""

# Test 8: Check if there are errors
echo "8. Check for Errors in Response"
echo "   Command: GET /api/ops/exceptions/today | jq '{hasErrors, errorMessage}'"
echo "   ---"
curl -s -X GET "${BASE_URL}/api/ops/exceptions/today" | jq '{hasErrors, errorMessage}' 2>/dev/null
echo ""
echo ""

echo "================================================"
echo "Test Summary"
echo "================================================"
echo ""
echo "If all endpoints return valid JSON with 200 OK status:"
echo "✅ Azure integration is working correctly"
echo ""
echo "If you see error responses:"
echo "❌ Check AZURE_SETUP_QUICK.md for troubleshooting"
echo ""
echo "Response Fields Explained:"
echo "  - totalCount: Total exceptions found today"
echo "  - countByApp: Number of exceptions per application"
echo "  - latestExceptions: Array of most recent exceptions"
echo "  - queryPeriod: Time period queried (e.g., 'Today')"
echo "  - hasErrors: Whether query execution had errors"
echo "  - errorMessage: Error details if hasErrors is true"
echo ""

