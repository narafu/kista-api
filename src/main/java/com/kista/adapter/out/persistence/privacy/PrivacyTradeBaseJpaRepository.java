package com.kista.adapter.out.persistence.privacy;

import com.kista.domain.model.strategy.Strategy.Ticker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface PrivacyTradeBaseJpaRepository extends JpaRepository<PrivacyTradeBaseEntity, UUID> {
    // 중복 체크용 — 정확한 날짜 일치 (>= 쓰면 미래 레코드를 잡아 false 409 발생)
    Optional<PrivacyTradeBaseEntity> findByTradeDateAndTicker(LocalDate tradeDate, Ticker ticker);

    Optional<PrivacyTradeBaseEntity> findFirstByTradeDateGreaterThanEqualAndTickerOrderByTradeDateAsc(LocalDate tradeDate, Ticker ticker);

    // N+1 방지: 주문(orders)을 join fetch, DISTINCT로 기준 매매표 중복 제거, 거래일 내림차순
    @Query("SELECT DISTINCT b FROM PrivacyTradeBaseEntity b LEFT JOIN FETCH b.orders "
            + "WHERE b.tradeDate >= :fromUtc ORDER BY b.tradeDate DESC")
    List<PrivacyTradeBaseEntity> findBasesFromTradeDate(LocalDate fromUtc);
}
