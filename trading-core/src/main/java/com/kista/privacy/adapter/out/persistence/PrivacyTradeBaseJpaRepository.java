package com.kista.privacy.adapter.out.persistence;

import com.kista.sharedkernel.StrategyTicker;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface PrivacyTradeBaseJpaRepository extends JpaRepository<PrivacyTradeBaseEntity, UUID> {
    // 중복 체크용 — 정확한 발행일 일치 (>= 쓰면 미래 레코드를 잡아 false 409 발생)
    Optional<PrivacyTradeBaseEntity> findByReleaseDateAndTicker(LocalDate releaseDate, StrategyTicker ticker);

    Optional<PrivacyTradeBaseEntity> findFirstByReleaseDateGreaterThanEqualAndTickerOrderByReleaseDateAsc(LocalDate releaseDate, StrategyTicker ticker);

    @EntityGraph(attributePaths = "orders")
    Optional<PrivacyTradeBaseEntity> findFirstWithOrdersByReleaseDateGreaterThanEqualAndTickerOrderByReleaseDateAsc(LocalDate releaseDate, StrategyTicker ticker);

    // N+1 방지: 주문(orders)을 join fetch, DISTINCT로 기준 매매표 중복 제거, 발행일 내림차순
    @Query("SELECT DISTINCT b FROM PrivacyTradeBaseEntity b LEFT JOIN FETCH b.orders "
            + "WHERE b.releaseDate >= :fromReleaseDate ORDER BY b.releaseDate DESC")
    List<PrivacyTradeBaseEntity> findBasesFromReleaseDate(LocalDate fromReleaseDate);
}
