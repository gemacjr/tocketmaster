package com.tocketmaster.event.repo;

import com.tocketmaster.event.domain.VenueEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface VenueRepository extends JpaRepository<VenueEntity, UUID> {
}
