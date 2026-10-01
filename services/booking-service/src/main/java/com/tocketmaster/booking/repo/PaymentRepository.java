package com.tocketmaster.booking.repo;

import com.tocketmaster.booking.domain.PaymentEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {

    Optional<PaymentEntity> findByProviderRef(String providerRef);

    Optional<PaymentEntity> findByIdempotencyKey(String idempotencyKey);

    /** Pessimistic lock for webhook delivery: Stripe retries can race duplicates. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PaymentEntity> findWithLockByProviderRef(String providerRef);
}
