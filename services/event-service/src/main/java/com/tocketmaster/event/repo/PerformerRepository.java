package com.tocketmaster.event.repo;

import com.tocketmaster.event.domain.PerformerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PerformerRepository extends JpaRepository<PerformerEntity, UUID> {
}
