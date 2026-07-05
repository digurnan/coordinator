# Document Search Service (Java)

Distributed document search prototype implementing the [architecture design](docs/ARCHITECTURE.md): multi-tenant full-text search with **Spring Boot**, **Elasticsearch**, and **Redis**.

## Architecture (prototype)

```
Client → Spring Boot API → Redis (cache + rate limits) → Elasticsearch
```

| Component | Role |
|---|---|
| **Search API** | REST endpoints, tenant validation, rate limiting |
| **Redis** | Search cache (`search:{tenant}:{hash}`), document cache (`doc:{tenant}:{id}`), rate limit counters |
| **Elasticsearch** | Shared `documents` index with `tenant_id` filter |

## Quick Start

```bash
docker compose up --build
./scripts/sample_requests.sh
```

Or:

```bash
make up
make demo
```

API: **http://localhost:8080**

## API Endpoints

| Method | Path | Description |
|---|---|---|
| POST | `/documents` | Index a new document |
| GET | `/search?q={query}` | Full-text search (tenant via header or query) |
| GET | `/documents/{id}` | Retrieve document by ID |
| DELETE | `/documents/{id}` | Delete a document |
| GET | `/health` | Health check (Elasticsearch + Redis) |

**Header:** `X-Tenant-ID: tenant_acme` (or `?tenant=tenant_acme`)

### Example

```bash
curl -X POST http://localhost:8080/documents \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: tenant_acme" \
  -d '{"title":"Q1 Report","body":"Revenue increased 12% year over year."}'

curl "http://localhost:8080/search?q=revenue" -H "X-Tenant-ID: tenant_acme"
```

## Project Layout

```
document-search-service/
├── docs/                    # Requirements, architecture, production analysis
├── postman/                 # Postman collection
├── scripts/                 # Sample curl demo
├── src/main/java/com/example/search/
│   ├── config/              # ES client, Redis, Jackson, properties
│   ├── controller/          # REST layer
│   ├── service/             # Elasticsearch, cache, rate limiter
│   ├── web/                 # Tenant filter + resolver
│   ├── validation/          # Document size limits
│   ├── dto/                 # API contracts
│   └── model/               # Elasticsearch document model
├── Dockerfile               # Multi-stage Maven → JRE 21
└── docker-compose.yml       # API + Elasticsearch + Redis
```

## Documentation

| Document | Description |
|---|---|
| [REQUIREMENTS.md](docs/REQUIREMENTS.md) | Functional requirements & API contract |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | System design, data flows, caching |
| [PRODUCTION_READINESS.md](docs/PRODUCTION_READINESS.md) | Scale, security, SLA |
| [SUBMISSION.md](docs/SUBMISSION.md) | Deliverables checklist |

## Local Development

Requires Java 21, Maven, Elasticsearch `:9200`, Redis `:6379`:

```bash
mvn spring-boot:run
mvn test
```

## Tech Stack

- Java 21 + Spring Boot 3.4
- Elasticsearch 8.17 (BM25, fuzzy match, highlighting)
- Redis 7 (cache-aside, sliding-window rate limit)
- Docker Compose multi-service stack

## Design Features Implemented

- Multi-tenant isolation (`tenant_id` filter on every ES query)
- Search result cache (60s TTL) + document cache (300s TTL)
- Per-tenant rate limiting (100 req/min, HTTP 429 + Retry-After)
- Tenant middleware filter on all data endpoints
- 1 MB document body limit
- Health endpoint with dependency status
