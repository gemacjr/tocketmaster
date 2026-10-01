package com.tocketmaster.event.repo;

import com.tocketmaster.event.domain.EventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface EventRepository extends JpaRepository<EventEntity, UUID> {

    Page<EventEntity> findByStartsAtAfterOrderByStartsAtAsc(OffsetDateTime now, Pageable pageable);

    Page<EventEntity> findByCategoryAndStartsAtAfterOrderByStartsAtAsc(
            String category, OffsetDateTime now, Pageable pageable);
}
