package com.kista.adapter.out.persistence.privacy;

import com.kista.domain.model.strategy.Strategy.Ticker;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

interface PrivacyTradeMasterJpaRepository extends JpaRepository<PrivacyTradeMasterEntity, UUID> {
    Optional<PrivacyTradeMasterEntity> findByTradeDateAndTicker(LocalDate tradeDate, Ticker ticker);

    // trade_date >= today인 행 중 가장 미래 거래일의 SOXL 기준가 조회
    Optional<PrivacyTradeMasterEntity> findFirstByTradeDateGreaterThanEqualAndTickerOrderByTradeDateDesc(
            LocalDate today, Ticker ticker);
}
