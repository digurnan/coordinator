# Enterprise Experience Showcase

> **Note:** Replace placeholder examples below with your real experience before submission.

---

## 1. Similar Distributed System Built

At [Company], I led the design of a multi-tenant log search platform serving 500+ enterprise customers. The system indexed 2TB/day of structured logs into Elasticsearch across 12 shards, with a Kafka ingestion pipeline and Redis caching layer. We achieved p95 search latency of 180ms at 5,000 QPS. The platform reduced customer incident investigation time by 40% and became a core differentiator for the observability product.

---

## 2. Performance Optimization

In a document retrieval API handling 50M records, search latency had degraded to 2+ seconds p95 due to unoptimized Elasticsearch queries scoring on filtered fields. I refactored queries to use `filter` context for tenant and date range (no scoring overhead), added a Redis cache for top 1000 queries per tenant, and implemented bulk indexing with 500-document batches. Result: p95 dropped from 2100ms to 85ms (96% improvement) and ES CPU utilization fell 60%.

---

## 3. Critical Production Incident Resolved

During a Black Friday traffic spike, our search cluster entered yellow state due to shard relocation storms triggered by auto-scaling adding nodes mid-peak. Searches timed out at 30% error rate. I immediately disabled auto-scaling, pinned shard allocation to stable nodes, increased replica count temporarily, and routed read traffic to a warm standby cluster. Recovery took 45 minutes; post-incident we implemented allocation awareness and pre-warmed clusters before known peak events.

---

## 4. Architectural Trade-off Decision

When designing tenant isolation, we debated index-per-tenant vs. shared index with filters. Index-per-tenant offered stronger isolation but would exceed Elasticsearch's recommended index count at 2000+ tenants. I chose shared index with mandatory `tenant_id` filter, index-level access controls, and dedicated indices only for enterprise tier (>10M docs). This balanced operational simplicity, cost ($40K/month savings vs. separate clusters), and security (validated via penetration testing).

---

## AI Tool Usage

This submission was scaffolded using Cursor AI (Claude) for:
- Initial project structure and Docker Compose configuration
- Architecture document outline and API contract
- Boilerplate Spring Boot REST controllers and Elasticsearch Java client integration

All design decisions, trade-off analysis, and experience examples were reviewed and customized by the author.
