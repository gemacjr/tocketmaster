package com.tocketmaster.search.svc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Minimal OpenSearch client over Spring's RestClient — just the three operations we
 * need (ensure index, index doc, search). Hand-rolled JSON keeps the dependency
 * footprint tiny; swap in the official opensearch-java client if the query surface grows.
 */
@Component
public class OpenSearchClient {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchClient.class);
    public static final String INDEX = "events";

    private final RestClient rest;
    private final ObjectMapper mapper;

    public OpenSearchClient(@Value("${opensearch.url}") String baseUrl,
                            ObjectMapper mapper) {
        this.rest = RestClient.builder().baseUrl(baseUrl).build();
        this.mapper = mapper;
    }

    public boolean ping() {
        try {
            rest.get().uri("/").retrieve().body(String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized void ensureIndex() {
        boolean exists;
        try {
            rest.head().uri("/{index}", INDEX).retrieve().toBodilessEntity();
            exists = true;
        } catch (HttpClientErrorException.NotFound e) {
            exists = false;
        } catch (Exception e) {
            throw new IllegalStateException("OpenSearch unreachable: " + e.getMessage(), e);
        }
        if (exists) {
            return;
        }

        ObjectNode body = mapper.createObjectNode();
        ObjectNode props = body.putObject("mappings").putObject("properties");
        props.putObject("name").put("type", "text");
        props.putObject("description").put("type", "text");
        props.putObject("performerName").put("type", "text");
        props.putObject("venueName").put("type", "text");
        ObjectNode city = props.putObject("city");
        city.put("type", "text");
        city.putObject("fields").putObject("keyword").put("type", "keyword");
        props.putObject("category").put("type", "keyword");
        props.putObject("startsAt").put("type", "date");

        rest.put()
                .uri("/{index}", INDEX)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json(body))
                .retrieve()
                .toBodilessEntity();
        log.info("Created OpenSearch index {}", INDEX);
    }

    private String json(Object node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }

    /** Index (upsert) a document. */
    public void indexDocument(String eventId, String json) {
        try {
            rest.put()
                    .uri("/{index}/_doc/{id}", INDEX, eventId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            throw new IllegalStateException("OpenSearch index failed: " + e.getMessage(), e);
        }
    }

    /**
     * Full-text + fuzzy search with filters. Returns the raw response tree; caller
     * extracts hits.
     */
    public JsonNode search(String q, String category, String city, int from, int size) {
        ObjectNode body = mapper.createObjectNode();
        body.put("from", from);
        body.put("size", size);

        ObjectNode bool = body.putObject("query").putObject("bool");
        ArrayNode must = bool.withArray("must");

        if (q == null || q.isBlank()) {
            must.add(mapper.createObjectNode().putObject("match_all"));
        } else {
            var mm = must.addObject().putObject("multi_match");
            mm.put("query", q);
            mm.putArray("fields")
                    .add("name^3")
                    .add("performerName^2")
                    .add("venueName")
                    .add("description")
                    .add("city");
            mm.put("fuzziness", "AUTO");
        }

        ArrayNode filter = bool.withArray("filter");
        var range = filter.addObject().putObject("range").putObject("startsAt");
        range.put("gte", "now");
        if (category != null && !category.isBlank()) {
            var term = filter.addObject().putObject("term");
            term.put("category", category.toUpperCase());
        }
        if (city != null && !city.isBlank()) {
            var match = filter.addObject().putObject("match").putObject("city");
            match.put("query", city);
            match.put("fuzziness", "AUTO");
        }

        String responseBody = rest.post()
                .uri("/{index}/_search", INDEX)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json(body))
                .retrieve()
                .body(String.class);
        try {
            return mapper.readTree(responseBody);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("OpenSearch returned invalid JSON", e);
        }
    }
}
