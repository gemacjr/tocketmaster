package com.tocketmaster.event.relay;

import com.tocketmaster.event.cache.EventCacheService;
import com.tocketmaster.event.domain.OutboxEntity;
import com.tocketmaster.event.repo.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CDC-lite relay: drains the outbox, evicts the event's read-through cache and
 * upserts a search document into OpenSearch via the Search Service.
 *
 * Failure model: if search is down, rows stay unprocessed and are retried on the
 * next tick — indexing lags but never loses mutations. Production would swap this
 * for Debezium -> MSK -> indexer (docs/08-search.md); the contract stays identical.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH = 50;

    private final OutboxRepository outbox;
    private final EventCacheService cache;
    private final RestClient restClient;

    public OutboxRelay(OutboxRepository outbox,
                       EventCacheService cache,
                       @Value("${search.service-url}") String searchServiceUrl) {
        this.outbox = outbox;
        this.cache = cache;
        this.restClient = RestClient.builder().baseUrl(searchServiceUrl).build();
    }

    @Scheduled(fixedDelayString = "${relay.poll-interval-ms:2000}")
    public void drain() {
        List<OutboxEntity> batch = outbox.findUnprocessed(BATCH);
        for (OutboxEntity row : batch) {
            try {
                relayOne(row);
                row.setProcessedAt(OffsetDateTime.now());
                outbox.save(row);
            } catch (Exception e) {
                log.warn("Relay failed for {} {} (will retry): {}",
                        row.getAggregateType(), row.getAggregateId(), e.getMessage());
            }
        }
    }

    private void relayOne(OutboxEntity row) {
        if (!"EVENT".equals(row.getAggregateType())) {
            return;
        }
        var eventId = row.getAggregateId();
        cache.evict(eventId);

        var detail = cache.loadFromDb(eventId);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("eventId", detail.id().toString());
        doc.put("name", detail.name());
        doc.put("description", detail.description());
        doc.put("category", detail.category());
        doc.put("startsAt", detail.startsAt() == null ? null : detail.startsAt().toString());
        doc.put("performerName", detail.performer() == null ? null : detail.performer().name());
        doc.put("venueName", detail.venue() == null ? null : detail.venue().name());
        doc.put("city", detail.venue() == null ? null : detail.venue().city());

        restClient.post()
                .uri("/internal/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .body(doc)
                .retrieve()
                .toBodilessEntity();
    }
}
