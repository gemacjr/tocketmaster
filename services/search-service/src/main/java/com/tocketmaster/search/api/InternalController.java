package com.tocketmaster.search.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.tocketmaster.search.svc.SearchService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
public class InternalController {

    private final SearchService searchService;

    public InternalController(SearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * POST /internal/documents — upsert one event document. Called by the
     * Event Service's outbox relay (in prod: CDC consumer). Retries are safe:
     * indexing is an idempotent upsert by eventId.
     */
    @PostMapping(path = "/internal/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> upsert(@RequestBody JsonNode body) {
        String eventId = body.path("eventId").asText(null);
        if (eventId == null || eventId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "eventId required");
        }
        searchService.upsert(eventId, body);
        return Map.of("indexed", eventId);
    }

    /** POST /api/admin/search/reindex — rebuild the index from PostgreSQL. */
    @PostMapping("/api/admin/search/reindex")
    public Map<String, Object> reindex() {
        int n = searchService.reindex();
        return Map.of("indexed", n);
    }
}
