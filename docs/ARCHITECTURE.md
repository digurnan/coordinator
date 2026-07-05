# Architecture Design Document
## Distributed Document Search Service

---

## 1. High-Level Architecture

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Client    │     │   Client    │     │   Client    │
│  (Tenant A) │     │  (Tenant B) │     │  (Tenant C) │
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘
       │                   │                   │
       └───────────────────┼───────────────────┘
                           │ HTTPS
                    ┌──────▼──────┐
                    │  API Gateway │  (production: Kong/AWS ALB)
                    │ Rate Limit   │
                    │ Auth (JWT)   │
                    └──────┬──────┘
                           │
              ┌────────────┼────────────┐
              │            │            │
       ┌──────▼──────┐ ┌───▼───┐ ┌──────▼──────┐
       │  Search API │ │Search │ │  Search API │
       │ (Spring Boot)│ │ API   │ │ (Spring Boot)│
       └──────┬──────┘ └───┬───┘ └──────┬──────┘
              │            │            │
       ┌──────▼────────────▼────────────▼──────┐
       │              Redis Cache               │
       │   (search results, rate limit counters)│
       └──────────────────┬────────────────────┘
                          │
       ┌──────────────────▼────────────────────┐
       │         Elasticsearch Cluster          │
       │  Index: documents (tenant_id filter)   │
       │  Shards: 5 primary, 1 replica          │
       └──────────────────┬────────────────────┘
                          │
       ┌──────────────────▼────────────────────┐
       │      Message Queue (production)        │
       │   Kafka/RabbitMQ for async indexing    │
       └───────────────────────────────────────┘
```

**Prototype simplification:** Single API instance, Redis, and single-node Elasticsearch via Docker Compose.

---

## 2. Data Flow

### Indexing Flow
```
Client → POST /documents
  → Validate tenant + payload
  → Rate limit check (Redis)
  → Index document in Elasticsearch (tenant_id field)
  → Invalidate tenant search cache (Redis)
  → Return 201 + document ID
```

### Search Flow
```
Client → GET /search?q=...&tenant=...
  → Rate limit check (Redis)
  → Cache lookup: search:{tenant}:{query_hash}
  → Cache HIT → return cached results
  → Cache MISS → Elasticsearch bool query:
       must: match title/body
       filter: term tenant_id = {tenant}
  → Store results in Redis (TTL 60s)
  → Return ranked results
```

---

## 3. Storage Strategy

| Layer | Technology | Purpose |
|---|---|---|
| Search engine | Elasticsearch 8.x | Full-text indexing, BM25 relevance, aggregations |
| Cache | Redis 7 | Search result cache, rate limit counters |
| Metadata (prod) | PostgreSQL | Tenant config, audit logs, document metadata backup |
| Object store (prod) | S3/GCS | Large document attachments |

**Why Elasticsearch:** Native full-text search, horizontal sharding, sub-100ms queries at scale, tenant filtering via `term` queries on `tenant_id`.

**Index mapping:**
```json
{
  "mappings": {
    "properties": {
      "tenant_id": { "type": "keyword" },
      "title": { "type": "text", "analyzer": "english" },
      "body": { "type": "text", "analyzer": "english" },
      "metadata": { "type": "object", "enabled": false },
      "created_at": { "type": "date" }
    }
  }
}
```

---

## 4. Multi-Tenancy Strategy

**Approach:** Shared index with tenant isolation via `tenant_id` keyword field.

| Strategy | Pros | Cons | Choice |
|---|---|---|---|
| Index per tenant | Strong isolation | 1000s of indices at scale | No |
| Shared index + filter | Simple, efficient | Requires strict query filters | **Yes (prototype + prod)** |
| Separate clusters | Maximum isolation | High cost | Enterprise tier only |

Every query includes a mandatory `filter: { term: { tenant_id } }` clause. API middleware rejects requests without valid tenant ID.

---

## 5. Caching Strategy

| Cache | Key Pattern | TTL | Invalidation |
|---|---|---|---|
| Search results | `search:{tenant}:{hash(q)}` | 60s | On document index/delete for tenant |
| Rate limits | `ratelimit:{tenant}:{minute}` | 60s | Sliding window, auto-expire |
| Document by ID | `doc:{tenant}:{id}` | 300s | On update/delete |

Cache-aside pattern: read through Redis, write-through on miss, invalidate on writes.

---

## 6. Consistency Model

- **Search index:** Eventually consistent (Elasticsearch near-real-time, ~1s refresh).
- **Cache:** Eventually consistent; TTL-bound staleness acceptable for search.
- **Trade-off:** Favor availability and latency over strong consistency. A newly indexed document may not appear in search for up to 1 second (Elasticsearch refresh interval).

Production enhancement: use `refresh=wait_for` on critical writes or reduce refresh interval for hot tenants.

---

## 7. Message Queue (Production)

Async indexing path for high write throughput:
```
POST /documents → API validates → publish to Kafka topic "doc-index"
  → Indexer workers consume → bulk index to Elasticsearch
  → Invalidate cache → publish "doc-indexed" event
```

Benefits: decouple write latency from indexing, enable retry/dead-letter, burst absorption.

---

## 8. API Design Summary

| Method | Path | Description |
|---|---|---|
| POST | /documents | Index document |
| GET | /search | Full-text search |
| GET | /documents/{id} | Get document |
| DELETE | /documents/{id} | Delete document |
| GET | /health | Dependency health |

All endpoints require `X-Tenant-ID` header.
