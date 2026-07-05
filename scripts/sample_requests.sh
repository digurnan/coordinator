#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TENANT="tenant_acme"
OTHER_TENANT="tenant_beta"

echo "=== Health Check ==="
curl -s "$BASE_URL/health" | python3 -m json.tool

echo -e "\n=== Index Document (tenant_acme) ==="
RESP=$(curl -s -X POST "$BASE_URL/documents" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: $TENANT" \
  -d '{"title":"Q1 Revenue Report","body":"Revenue increased 12% year over year in Q1 2026.","metadata":{"category":"finance"}}')
echo "$RESP" | python3 -m json.tool
DOC_ID=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")

echo -e "\n=== Index Another Document ==="
curl -s -X POST "$BASE_URL/documents" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: $TENANT" \
  -d '{"title":"Engineering Roadmap","body":"Distributed search service with Elasticsearch and Redis caching."}' \
  | python3 -m json.tool

echo -e "\n=== Search: revenue ==="
curl -s "$BASE_URL/search?q=revenue" -H "X-Tenant-ID: $TENANT" | python3 -m json.tool

echo -e "\n=== Get Document by ID ==="
curl -s "$BASE_URL/documents/$DOC_ID" -H "X-Tenant-ID: $TENANT" | python3 -m json.tool

echo -e "\n=== Tenant Isolation Test (tenant_beta cannot see tenant_acme doc) ==="
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/documents/$DOC_ID" -H "X-Tenant-ID: $OTHER_TENANT")
echo "Expected 404, got: $HTTP_CODE"

echo -e "\n=== Delete Document ==="
curl -s -o /dev/null -w "HTTP %{http_code}\n" -X DELETE "$BASE_URL/documents/$DOC_ID" -H "X-Tenant-ID: $TENANT"

echo -e "\n=== Verify Deletion ==="
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/documents/$DOC_ID" -H "X-Tenant-ID: $TENANT")
echo "Expected 404, got: $HTTP_CODE"

echo -e "\nDone."
