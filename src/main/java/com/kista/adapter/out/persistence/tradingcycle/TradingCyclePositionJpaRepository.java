package com.kista.adapter.out.persistence.tradingcycle;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface TradingCyclePositionJpaRepository extends JpaRepository<TradingCyclePositionEntity, UUID> {

    List<TradingCyclePositionEntity> findTop10ByTradingCycleIdOrderByCreatedAtDesc(UUID tradingCycleId);

    // 계좌 ID 기준 날짜 범위 조회 — TradingCycleEntity의 @SQLRestriction(deleted_at IS NULL)이 서브쿼리에도 적용됨
    @Query("SELECT tcp FROM TradingCyclePositionEntity tcp " +
           "WHERE tcp.tradingCycleId IN " +
           "(SELECT tc.id FROM TradingCycleEntity tc WHERE tc.accountId = :accountId) " +
           "AND tcp.createdAt >= :from AND tcp.createdAt < :to " +
           "ORDER BY tcp.createdAt DESC")
    List<TradingCyclePositionEntity> findByAccountIdAndDateRange(
            @Param("accountId") UUID accountId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    // 전체 이력 최근 N건 — 대시보드·텔레그램 현황용
    List<TradingCyclePositionEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // 전략(사이클) ID 기준 날짜 범위 조회
    @Query("SELECT tcp FROM TradingCyclePositionEntity tcp " +
           "WHERE tcp.tradingCycleId = :cycleId " +
           "AND tcp.createdAt >= :from AND tcp.createdAt < :to " +
           "ORDER BY tcp.createdAt DESC")
    List<TradingCyclePositionEntity> findByCycleIdAndDateRange(
            @Param("cycleId") UUID cycleId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    // 날짜 범위 이력 전체 — 차트용 시계열 (from 이상 to 미만)
    @Query("SELECT tcp FROM TradingCyclePositionEntity tcp " +
           "WHERE tcp.createdAt >= :from AND tcp.createdAt < :to " +
           "ORDER BY tcp.createdAt DESC")
    List<TradingCyclePositionEntity> findBetweenDates(@Param("from") Instant from, @Param("to") Instant to);

    // 계좌 기준 커서 페이지네이션 — createdAt < cursor AND createdAt >= from, DESC
    @Query("SELECT tcp FROM TradingCyclePositionEntity tcp " +
           "WHERE tcp.tradingCycleId IN " +
           "(SELECT tc.id FROM TradingCycleEntity tc WHERE tc.accountId = :accountId) " +
           "AND tcp.createdAt >= :from AND tcp.createdAt < :cursor " +
           "ORDER BY tcp.createdAt DESC")
    List<TradingCyclePositionEntity> findByAccountIdWithCursor(
            @Param("accountId") UUID accountId,
            @Param("from") Instant from,
            @Param("cursor") Instant cursor,
            Pageable pageable);

    // 전략(사이클) 기준 커서 페이지네이션
    @Query("SELECT tcp FROM TradingCyclePositionEntity tcp " +
           "WHERE tcp.tradingCycleId = :cycleId " +
           "AND tcp.createdAt >= :from AND tcp.createdAt < :cursor " +
           "ORDER BY tcp.createdAt DESC")
    List<TradingCyclePositionEntity> findByCycleIdWithCursor(
            @Param("cycleId") UUID cycleId,
            @Param("from") Instant from,
            @Param("cursor") Instant cursor,
            Pageable pageable);
}
