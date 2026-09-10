package com.kista.trading.application.usecase;

import com.kista.trading.domain.model.CycleHistoryPage;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.RegisterStrategyCommand;
import com.kista.trading.domain.model.StrategyDetail;
import com.kista.trading.domain.model.StrategySeedPreview;
import com.kista.trading.domain.model.UpdateStrategyCommand;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface StrategyUseCase {
    // --- 조회 ---
    List<StrategyDetail> listByAccountId(UUID accountId, UUID requesterId);
    StrategyDetail getById(UUID strategyId, UUID requesterId);
    // 로그인 사용자의 전 계좌 전략 목록 (계좌 무관 집계) — 모바일 전략 탭용
    List<StrategyDetail> listByUserId(UUID userId);

    // 전략 등록/수정 폼용 최소시드·기준가 미리보기 (등록 시점 minRequiredDeposit 계산과 동일 경로)
    StrategySeedPreview strategySeedPreview(UUID accountId, UUID requesterId,
            StrategyType type, StrategyTicker ticker, int divisionCount);

    // 전략(사이클) 기준 거래 이력 조회 — 커서 기반 페이지네이션
    CycleHistoryPage getByStrategy(UUID strategyId, UUID requesterId,
            LocalDate from, LocalDate to, Instant cursor, int size);

    // 전략(사이클) 기준 기간 내 주문 내역 조회 — 사용자 전략 상세 화면용
    List<Order> getOrdersByStrategy(UUID strategyId, UUID requesterId, LocalDate from, LocalDate to);

    // --- 등록 ---
    StrategyDetail register(UUID userId, UUID accountId, RegisterStrategyCommand command);

    // --- 수정 ---
    StrategyDetail update(UUID strategyId, UUID requesterId, UpdateStrategyCommand cmd);

    // --- 삭제 ---
    void delete(UUID strategyId, UUID requesterId);

    // --- 일시정지 / 재개 ---
    void pause(UUID strategyId, UUID requesterId);
    void resume(UUID strategyId, UUID requesterId);
}
