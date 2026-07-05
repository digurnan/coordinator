package com.example.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class ElasticsearchConfig {

    @Bean(destroyMethod = "close")
    RestClient elasticsearchRestClient(AppProperties properties) {
        return RestClient.builder(HttpHost.create(properties.elasticsearch().url())).build();
    }

    @Bean
    ElasticsearchClient elasticsearchClient(RestClient restClient) {
        ObjectMapper esMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper(esMapper));
        return new ElasticsearchClient(transport);
    }
}
