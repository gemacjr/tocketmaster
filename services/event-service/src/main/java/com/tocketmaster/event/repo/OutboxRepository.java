package com.tocketmaster.event.repo;

import com.tocketmaster.event.domain.OutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEntity, Long> {

    @Query("select o from OutboxEntity o where o.processedAt is null order by o.id asc limit :batch")
    List<OutboxEntity> findUnprocessed(int batch);
}
