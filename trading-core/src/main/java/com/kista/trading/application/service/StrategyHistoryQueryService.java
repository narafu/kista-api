package com.kista.trading.application.service;

import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.broker.application.port.output.BrokerPricePort;
import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.matching.domain.strategy.CycleOrderStrategies;
import com.kista.matching.domain.strategy.CycleOrderStrategy;
import com.kista.privacy.application.port.output.PrivacyTradePort;
import com.kista.privacy.domain.model.PrivacyCurrentBase;
import com.kista.privacy.domain.model.PrivacyTradeBase;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.TimeZones;
import com.kista.trading.application.port.output.CyclePositionPort;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.trading.domain.model.CycleHistoryPage;
import com.kista.trading.domain.model.CyclePositionHistoryEntry;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.Strategy;
import com.kista.trading.domain.model.StrategySeedPreview;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// 전략(사이클) 기준 조회 전용 — 시드 미리보기, 거래 이력, 주문 내역 (stats에서 이관됐던 전략 소유 read)
@Service
@RequiredArgsConstructor
class StrategyHistoryQueryService {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final CyclePositionPort cyclePositionPort;
    private final OrderPort orderPort;
    private final CycleOrderStrategies cycleStrategies;
    private final PrivacyTradePort privacyTradePort;
    private final BrokerAdapterRegistry registry;

    // 전략 등록/수정 폼용 최소시드·기준가 미리보기 — register()의 minRequiredDeposit 계산과 동일 경로
    // 브로커 HTTP(getPrevClose) 호출 포함 → 트랜잭션 없이 실행 (register()와 동일 이유)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    StrategySeedPreview strategySeedPreview(
            UUID accountId, UUID requesterId,
            StrategyType type, StrategyTicker ticker, int divisionCount) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);

        // 1단계: 전략 타입별 capability 로드
        CycleOrderStrategy strategy = cycleStrategies.of(type);

        // 2단계: PRIVACY 기준 매매표 조회 — 미리보기는 전일 DB trade_date를 잡지 않도록 스케쥴러 조회와 분리
        PrivacyCurrentBase currentBase = strategy.requiresPrivacyBase()
                ? privacyTradePort.findSeedPreviewBase().orElse(null)
                : null;
        if (strategy.requiresPrivacyBase() && currentBase == null) {
            return new StrategySeedPreview(ticker.name(), null, null, "NO_PRIVACY_BASE");
        }
        // PrivacyCycleOrderStrategy.minRequiredDeposit()은 currentCycleStart만 사용 — avgPrice 접근 없음
        PrivacyTradeBase privacyBase = currentBase != null
                ? new PrivacyTradeBase(null, null, 0, currentBase.currentCycleStart(), List.of())
                : null;

        // 3단계: 기준가 결정 후 최소 시드 계산 — 실제 첫 주문(holdings=0)과 동일하게 전일종가 사용
        BigDecimal price = strategy.requiresPrivacyBase()
                ? null
                : registry.require(account.toBrokerRef(), BrokerPricePort.class).getPrevClose(ticker, account.toBrokerRef());
        BigDecimal basePrice = strategy.requiresPrivacyBase()
                ? privacyBase.currentCycleStart()
                : price;
        BigDecimal minSeed = strategy.minRequiredDeposit(price, privacyBase, divisionCount);

        return new StrategySeedPreview(ticker.name(), basePrice, minSeed, null);
    }

    // 전략(사이클) 기준 거래 이력 조회 — 커서 기반 페이지네이션
    @Transactional(readOnly = true)
    CycleHistoryPage getByStrategy(UUID strategyId, UUID requesterId,
                                   LocalDate from, LocalDate to,
                                   Instant cursor, int size) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        Instant fromInstant = resolveHistoryFrom(from);
        Instant effectiveCursor = cursor != null ? cursor : resolveHistoryTo(to);
        List<CyclePositionHistoryEntry> raw =
                cyclePositionPort.findByStrategyIdWithCursor(strategyId, fromInstant, effectiveCursor, size + 1);
        boolean hasMore = raw.size() > size;
        List<CyclePositionHistoryEntry> items = hasMore ? raw.subList(0, size) : raw;
        Instant nextCursor = hasMore ? items.get(items.size() - 1).createdAt() : null;
        return new CycleHistoryPage(items, nextCursor, hasMore);
    }

    // 전략(사이클) 기준 기간 내 주문 내역 조회 — 사용자 전략 상세 화면용
    @Transactional(readOnly = true)
    List<Order> getOrdersByStrategy(UUID strategyId, UUID requesterId, LocalDate from, LocalDate to) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        return orderPort.findByStrategyId(strategyId, from, to);
    }

    // 이력 조회 커서 경계 — KST 자정 (stats getByAccount와 동일 규칙, 모듈 경계라 헬퍼 중복 유지)
    private Instant resolveHistoryFrom(LocalDate from) {
        return from != null ? from.atStartOfDay(TimeZones.KST).toInstant() : Instant.EPOCH;
    }

    private Instant resolveHistoryTo(LocalDate to) {
        var resolved = to != null ? to : LocalDate.now(TimeZones.KST);
        return resolved.plusDays(1).atStartOfDay(TimeZones.KST).toInstant(); // to 당일 포함
    }
}
