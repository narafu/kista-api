package com.kista.adapter.out.persistence.privacy;

import com.kista.domain.model.strategy.Strategy.Ticker;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

interface PrivacyTradeMasterJpaRepository extends JpaRepository<PrivacyTradeMasterEntity, UUID> {
    Optional<PrivacyTradeMasterEntity> findByTradeDateAndTicker(LocalDate tradeDate, Ticker ticker);
}
