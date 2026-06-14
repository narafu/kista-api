package com.kista.adapter.out.persistence.privacy;

import com.kista.domain.model.strategy.Strategy.Ticker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface PrivacyTradeMasterJpaRepository extends JpaRepository<PrivacyTradeMasterEntity, UUID> {
    // 중복 체크용 — 정확한 날짜 일치 (>= 쓰면 미래 레코드를 잡아 false 409 발생)
    Optional<PrivacyTradeMasterEntity> findByTradeDateAndTicker(LocalDate tradeDate, Ticker ticker);

    Optional<PrivacyTradeMasterEntity> findFirstByTradeDateGreaterThanEqualAndTickerOrderByTradeDateAsc(LocalDate tradeDate, Ticker ticker);

    // trade_date >= today인 행 중 가장 미래 거래일의 SOXL 기준가 조회
    Optional<PrivacyTradeMasterEntity> findFirstByTradeDateGreaterThanEqualAndTickerOrderByTradeDateDesc(
            LocalDate today, Ticker ticker);

    // N+1 방지: 주문(orders)을 join fetch, DISTINCT로 마스터 중복 제거, 거래일 내림차순
    @Query("SELECT DISTINCT m FROM PrivacyTradeMasterEntity m LEFT JOIN FETCH m.orders "
            + "WHERE m.tradeDate >= :fromUtc ORDER BY m.tradeDate DESC")
    List<PrivacyTradeMasterEntity> findBasesFromTradeDate(LocalDate fromUtc);
}
