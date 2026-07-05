# Submission Summary
## Distributed Document Search Service

Complete implementation aligned with [ARCHITECTURE.md](ARCHITECTURE.md) and [REQUIREMENTS.md](REQUIREMENTS.md).

---

## Deliverables

| # | Deliverable | Location |
|---|---|---|
| 1 | Requirements | [REQUIREMENTS.md](REQUIREMENTS.md) |
| 2 | Architecture design | [ARCHITECTURE.md](ARCHITECTURE.md) |
| 3 | Working prototype | `src/main/java/`, `Dockerfile`, `docker-compose.yml` |
| 4 | Production readiness | [PRODUCTION_READINESS.md](PRODUCTION_READINESS.md) |
| 5 | Experience showcase | [EXPERIENCE_SHOWCASE.md](EXPERIENCE_SHOWCASE.md) |
| 6 | Sample requests | [scripts/sample_requests.sh](../scripts/sample_requests.sh), [postman/](../postman/) |

---

## Run

```bash
docker compose up --build
./scripts/sample_requests.sh
```

API: http://localhost:8080

---

## Design → Code Mapping

| Design element | Implementation |
|---|---|
| Indexing flow (validate → rate limit → ES → cache invalidate) | `DocumentController.createDocument()` |
| Search flow (rate limit → cache → ES bool query → cache set) | `SearchController` + `CacheService` |
| `search:{tenant}:{hash}` cache, 60s TTL | `CacheService.setSearch()` |
| `doc:{tenant}:{id}` cache, 300s TTL | `CacheService.setDocument()` |
| `ratelimit:{tenant}:{minute}` sliding window | `RateLimiterService` |
| Shared index + `tenant_id` filter | `ElasticsearchService.search()` |
| Tenant middleware | `TenantRequiredFilter` |
| ES mapping (tenant_id, title, body, metadata, created_at) | `ElasticsearchService.ensureIndex()` |
| Health with ES + Redis status | `HealthController` |

---

## Stack

Java 21 · Spring Boot 3.4 · Elasticsearch 8.17 · Redis 7 · Docker Compose
