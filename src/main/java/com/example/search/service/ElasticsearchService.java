package com.example.search.service;

import com.example.search.config.AppProperties;
import com.example.search.dto.DocumentCreatedResponse;
import com.example.search.dto.DocumentResponse;
import com.example.search.dto.SearchResultItem;
import com.example.search.model.IndexedDocument;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ElasticsearchService {

    private final ElasticsearchClient client;
    private final String indexName;

    public ElasticsearchService(ElasticsearchClient client, AppProperties properties) {
        this.client = client;
        this.indexName = properties.elasticsearch().index();
    }

    @PostConstruct
    void ensureIndex() throws IOException {
        boolean exists = client.indices().exists(e -> e.index(indexName)).value();
        if (!exists) {
            client.indices().create(c -> c
                    .index(indexName)
                    .mappings(TypeMapping.of(m -> m
                            .properties("tenant_id", Property.of(p -> p.keyword(k -> k)))
                            .properties("title", Property.of(p -> p.text(t -> t.analyzer("english"))))
                            .properties("body", Property.of(p -> p.text(t -> t.analyzer("english"))))
                            .properties("metadata", Property.of(p -> p.object(o -> o.enabled(false))))
                            .properties("created_at", Property.of(p -> p.date(d -> d)))
                    ))
            );
        }
    }

    public boolean ping() {
        try {
            return client.ping().value();
        } catch (Exception ex) {
            return false;
        }
    }

    public DocumentCreatedResponse indexDocument(
            String tenantId,
            String title,
            String body,
            Map<String, Object> metadata
    ) throws IOException {
        String docId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        IndexedDocument document = new IndexedDocument(tenantId, title, body, metadata, now);

        IndexResponse response = client.index(i -> i
                .index(indexName)
                .id(docId)
                .document(document)
                .refresh(Refresh.True)
        );

        return new DocumentCreatedResponse(response.id(), tenantId, title, now);
    }

    public DocumentResponse getDocument(String tenantId, String docId) throws IOException {
        GetResponse<IndexedDocument> response = client.get(g -> g
                .index(indexName)
                .id(docId),
                IndexedDocument.class
        );

        if (!response.found() || response.source() == null) {
            return null;
        }
        IndexedDocument doc = response.source();
        if (!tenantId.equals(doc.tenantId())) {
            return null;
        }
        return new DocumentResponse(docId, doc.tenantId(), doc.title(), doc.body(), doc.metadata(), doc.createdAt());
    }

    public boolean deleteDocument(String tenantId, String docId) throws IOException {
        DocumentResponse existing = getDocument(tenantId, docId);
        if (existing == null) {
            return false;
        }
        DeleteResponse response = client.delete(d -> d.index(indexName).id(docId).refresh(Refresh.True));
        return response.result() != null;
    }

    public SearchResult search(String tenantId, String query, int size) throws IOException {
        Query tenantFilter = Query.of(q -> q.term(t -> t.field("tenant_id").value(tenantId)));

        SearchResponse<IndexedDocument> response = client.search(s -> s
                        .index(indexName)
                        .size(size)
                        .query(q -> q.bool(b -> b
                                .must(m -> m.multiMatch(mm -> mm
                                        .query(query)
                                        .fields("title^2", "body")
                                        .fuzziness("AUTO")
                                ))
                                .filter(tenantFilter)
                        ))
                        .highlight(h -> h.fields("body", f -> f.fragmentSize(150).numberOfFragments(1))),
                IndexedDocument.class
        );

        List<SearchResultItem> results = new ArrayList<>();
        for (Hit<IndexedDocument> hit : response.hits().hits()) {
            IndexedDocument source = hit.source();
            if (source == null) {
                continue;
            }
            String snippet = source.body();
            if (snippet != null && snippet.length() > 150) {
                snippet = snippet.substring(0, 150);
            }
            if (hit.highlight() != null && hit.highlight().containsKey("body") && !hit.highlight().get("body").isEmpty()) {
                snippet = hit.highlight().get("body").getFirst();
            }
            results.add(new SearchResultItem(
                    hit.id(),
                    source.title(),
                    snippet,
                    hit.score() != null ? hit.score() : 0.0,
                    source.createdAt()
            ));
        }

        long total = response.hits().total() != null ? response.hits().total().value() : results.size();
        return new SearchResult(total, response.took(), results);
    }

    public record SearchResult(long total, long tookMs, List<SearchResultItem> results) {}
}
