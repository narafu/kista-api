package com.kista.api.admin.application.service;

import com.kista.shared.contract.account.AccountSummaryResponse;
import com.kista.api.admin.domain.model.AdminAnomalies;
import com.kista.api.admin.domain.model.AdminStats;
import com.kista.api.admin.domain.model.AppErrorLog;
import com.kista.api.admin.domain.model.AuditLog;
import com.kista.shared.contract.trading.OrderResponse;
import com.kista.shared.contract.trading.StrategySummaryResponse;
import com.kista.shared.contract.trading.StrategyResponse;
import com.kista.shared.contract.privacy.PrivacyTradeBaseResponse;
import com.kista.api.admin.application.usecase.AdminQueryUseCase;
import com.kista.api.admin.application.port.output.*;
import com.kista.api.user.application.port.output.UserPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.UserStatus;

// 클래스 레벨 @Transactional(readOnly = true) 제거됨 — tradingQueryPort/privacyQueryPort가
// HTTP 어댑터(내부 API 호출)라 DB 트랜잭션 안에서 호출하면 커넥션 풀 자기잠금 위험이 있다
// (Hikari 커넥션을 쥔 채 같은 풀을 쓰는 자기 자신을 향해 새 HTTP 요청 → 풀 고갈 시 데드락).
// "@Transactional 내부 외부 시스템 호출 금지" 규칙(constraints.md) 적용 — 순수 DB 조회 메서드에만
// 개별로 @Transactional(readOnly = true)을 붙인다.
@Service
@RequiredArgsConstructor
class AdminQueryService implements AdminQueryUseCase {

    private final UserPort userPort;
    private final AccountQueryPort accountQueryPort; // 계좌 조회(contract)+countAll() — 내부 API 경유
    private final AuditLogPort auditLogPort;
    private final TradingQueryPort tradingQueryPort;
    private final PrivacyQueryPort privacyQueryPort;
    private final AppErrorLogPort appErrorLogPort;
    private final Clock clock; // 현재 시각 기준 — 테스트에서 Clock.fixed 주입

    @Override
    public AdminStats getStats() {
        // accountQueryPort.countAll()이 HTTP 어댑터(내부 API 호출)라 @Transactional 밖 —
        // 상태별 카운트를 단일 GROUP BY 쿼리로 조회 (countAll+countByStatus×3 직렬 호출 대체)
        Map<UserStatus, Long> byStatus = userPort.countGroupByStatus();
        long pendingCount = byStatus.getOrDefault(UserStatus.PENDING, 0L);
        long activeCount = byStatus.getOrDefault(UserStatus.ACTIVE, 0L);
        long rejectedCount = byStatus.getOrDefault(UserStatus.REJECTED, 0L);
        long totalUsers = pendingCount + activeCount + rejectedCount;
        long totalAccounts = accountQueryPort.countAll();
        return new AdminStats(totalUsers, pendingCount, activeCount, rejectedCount, totalAccounts);
    }

    @Override
    public List<AccountSummaryResponse> listAccounts(LocalDate from, LocalDate to) {
        // HTTP 어댑터(내부 API 호출)라 @Transactional 밖 — 필터링은 내부 API 쪽(AccountInternalController)이 수행
        return accountQueryPort.findAll(from, to);
    }

    @Override
    public List<OrderResponse> listTrades(LocalDate from, LocalDate to) {
        // from 미지정 시 기본값: 최근 30일 — orders 테이블 전체 로드 방지
        LocalDate f = from != null ? from : LocalDate.now(clock).minusDays(30);
        LocalDate t = to   != null ? to   : LocalDate.now(clock);
        return tradingQueryPort.findAllOrders(f, t);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditLog> listAuditLogs(Instant from, Instant to) {
        if (from == null && to == null) return auditLogPort.findAll();
        Instant f = from != null ? from : Instant.EPOCH;
        Instant t = to   != null ? to   : Instant.now(clock);
        return auditLogPort.findAll(f, t);
    }

    @Override
    public AdminAnomalies getAnomalies(int inactiveDays, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        LocalDate rangeFrom = from != null ? from : today.minusDays(inactiveDays);
        LocalDate rangeTo   = to   != null ? to   : today;
        List<AccountSummaryResponse> allAccounts = accountQueryPort.findAll(null, null);

        // 배치 조회: 모든 계좌의 전략을 한 번에 조회 (N+1 방지)
        Map<UUID, List<StrategyResponse>> strategiesByAccountId = tradingQueryPort.findStrategiesByAccountIds(
                allAccounts.stream().map(AccountSummaryResponse::id).collect(Collectors.toSet()));

        // PAUSED 전략이 있는 계좌
        List<AccountSummaryResponse> pausedAccounts = allAccounts.stream()
                .filter(a -> strategiesByAccountId.getOrDefault(a.id(), List.of()).stream()
                        .anyMatch(st -> st.status() == StrategyStatus.PAUSED))
                .toList();

        // 범위 내 거래 있는 accountId 집합 (distinct만 필요 → 별도 쿼리로 최소 데이터 로드)
        Set<UUID> activeAccountIds = new HashSet<>(
                tradingQueryPort.findDistinctAccountIds(rangeFrom, rangeTo));

        // ACTIVE 전략이 있지만 범위 내 거래 없는 계좌
        List<AccountSummaryResponse> inactiveAccounts = allAccounts.stream()
                .filter(a -> strategiesByAccountId.getOrDefault(a.id(), List.of()).stream()
                        .anyMatch(st -> st.status() == StrategyStatus.ACTIVE))
                .filter(a -> !activeAccountIds.contains(a.id()))
                .toList();

        return new AdminAnomalies(pausedAccounts, inactiveAccounts);
    }

    @Override
    public Map<UUID, StrategySummaryResponse> getStrategySummariesByCycleIds(Set<UUID> cycleIds) {
        return tradingQueryPort.findStrategySummariesByCycleIds(cycleIds);
    }

    @Override
    public List<PrivacyTradeBaseResponse> listPrivacyBases(Integer days) {
        // days==null → 전체(EPOCH부터). 그 외 KST 기준 최근 N일 발행분 (release_date는 KST 발행일 원본)
        LocalDate fromReleaseDate = days == null
                ? LocalDate.EPOCH
                : LocalDate.now(clock).minusDays(days);
        return privacyQueryPort.findBasesFromTradeDate(fromReleaseDate);
    }

    @Override
    public List<StrategyResponse> listStrategies(UUID accountId) {
        return tradingQueryPort.findStrategiesByAccountId(accountId);
    }

    @Override
    public Map<UUID, List<StrategyResponse>> listStrategiesByAccountIds(Set<UUID> accountIds) {
        return tradingQueryPort.findStrategiesByAccountIds(accountIds);
    }

    @Override
    public List<LocalDate> listStrategyTradeDates(UUID accountId, UUID strategyId) {
        return tradingQueryPort.findStrategyTradeDates(accountId, strategyId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AppErrorLog> listErrorLogs(int limit, Instant from, Instant to) {
        return appErrorLogPort.findRecent(limit, from, to);
    }

    @Override
    @Transactional
    public void deleteErrorLog(UUID id) {
        appErrorLogPort.softDelete(id);
    }
}
