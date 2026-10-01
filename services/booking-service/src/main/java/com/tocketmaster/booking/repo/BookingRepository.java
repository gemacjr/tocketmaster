package com.tocketmaster.booking.repo;

import com.tocketmaster.booking.domain.BookingEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<BookingEntity, UUID> {

    List<BookingEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    @Query("""
            select b from BookingEntity b
            where b.status = 'PENDING' and b.expiresAt < :now
            """)
    List<BookingEntity> findExpiredPending(OffsetDateTime now);

    /** CAS-style transitions so sweep/cancel can never clobber a concurrent confirm. */
    @Modifying
    @Query("update BookingEntity b set b.status = 'EXPIRED' where b.id = :id and b.status = 'PENDING'")
    int expireIfPending(UUID id);

    @Modifying
    @Query("update BookingEntity b set b.status = 'CANCELLED' where b.id = :id and b.status = 'PENDING'")
    int cancelIfPending(UUID id);
}
