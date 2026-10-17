package com.kista.tradingcore.trading.application.port.output;

import com.kista.shared.kernel.OrderStatus;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.SubmittingOrder;
import com.kista.shared.kernel.StrategyTicker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface OrderPort {
    // 계획 주문 일괄 저장 (신규 PLANNED 상태)
    void saveAll(List<Order> orders);

    // 주문 단건 저장 후 id가 채워진 도메인 반환 (재주문 즉시 접수 — 선점 전 id 확보용)
    Order save(Order order);

    // 특정 사이클·날짜의 PLANNED 주문 조회 (waitForOrderTime 이후 실행 단계에서 호출)
    List<Order> findPlannedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate);

    // 특정 사이클·날짜의 PLACED 주문 조회 (수동 실행 감지 및 이중 실행 방지용)
    List<Order> findPlacedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate);

    // 증권사 접수 직전 선점 — PLANNED에서만 SUBMITTING 전이, 그 사이 취소·삭제·재캡됐으면 OrderStateConflictException(HTTP를 보내지 않는다)
    void markSubmitting(UUID orderId);

    // 증권사 주문 접수 완료 후 PLACED 상태 + externalOrderId 기록 — SUBMITTING에서만 전이(같은 접수번호 PLACED면 멱등), 아니면 OrderStateConflictException
    void markPlaced(UUID orderId, String externalOrderId);

    // 사이클·거래일의 접수 진행 중(SUBMITTING) 주문 + 선점 시각 — 잔류 대사·취소 대기·리포트 경고용
    List<SubmittingOrder> findSubmittingByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate);

    // 사이클·거래일 내 같은 증권사 주문번호 주문 존재 여부 (수동 체결 보정 중복 반영 방지 — 주문번호는 일자별 채번)
    boolean existsByStrategyCycleIdAndTradeDateAndExternalOrderId(UUID strategyCycleId, LocalDate tradeDate, String externalOrderId);

    // 증권사 미접수(PLANNED) 주문 취소 — PLANNED에서만 전이, 배치가 먼저 접수했거나 삭제됐으면 OrderStateConflictException
    void cancelPlanned(UUID orderId);

    // 사용자 스코프 기간+종목 조회 (대시보드 — 본인 데이터만)
    List<Order> findByUser(UUID userId, LocalDate from, LocalDate to, StrategyTicker ticker);

    // 기간 내 전체 계좌 조회 ticker 필터 없음 (관리자·이상징후 감지용)
    List<Order> findAll(LocalDate from, LocalDate to);

    // 기간 내 distinct accountId 목록 조회 (이상징후 감지용 N+1 방지)
    List<UUID> findDistinctAccountIdsByTradeDateBetween(LocalDate from, LocalDate to);

    // 단건 조회 (취소 전 상태 확인용)
    Optional<Order> findById(UUID orderId);

    // 사이클·거래일의 PLANNED 주문 일괄 삭제 — 접수 중 PLACED로 바뀐 행은 지우지 않음, 실제 삭제 건수 반환
    int deletePlannedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate);

    // 오늘 열린 주문(PLANNED·SUBMITTING·PLACED) 조회 (스케쥴러 재계산 skip·슬롯 점유·수동 실행 이중 실행 판정용)
    List<Order> findPlannedOrPlacedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate);

    // 여러 사이클의 오늘 열린 주문(PLANNED·SUBMITTING·PLACED) 배치 조회 (미리보기 N+1 방지) — strategyCycleId 기준 그룹핑
    Map<UUID, List<Order>> findPlannedOrPlacedByCycleIdsAndDate(Collection<UUID> strategyCycleIds, LocalDate tradeDate);

    // 취소 완료 또는 미체결 → CANCELLED 상태로 변경
    void markCancelled(UUID orderId);

    // 증권사 확정 거절 → FAILED 상태로 변경 — SUBMITTING에서만 전이, 아니면 OrderStateConflictException
    void markFailed(UUID orderId);

    // 체결 완료 → FILLED 또는 PARTIALLY_FILLED 상태 + 체결 수량·가중평균가 기록
    void markFilled(UUID orderId, BigDecimal filledQuantity, BigDecimal filledPrice, OrderStatus status);

    // 계좌 기준 당일 PLANNED·SUBMITTING BUY 주문 합계 (예수금 체크 — 타 전략 점유분 차감용, SUBMITTING은 보수적 이중 차감)
    BigDecimal sumPlannedBuyByAccountAndDate(UUID accountId, LocalDate tradeDate);

    // 계좌·거래일·종목 기준 PLANNED/SUBMITTING/PLACED SELL 예약 수량 합계 (판매가능수량 중복 배정 방지용)
    int sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(UUID accountId, LocalDate tradeDate, StrategyTicker ticker);

    // 계좌 기준 FILLED/PARTIALLY_FILLED 주문 조회 (일별 거래내역 달력용)
    List<Order> findFilledByAccount(UUID accountId, LocalDate from, LocalDate to);

    // 유저 기준 FILLED/PARTIALLY_FILLED 주문 조회 (대시보드 배치 조회 — 보유 계좌 전체 합산, 1회 조회)
    List<Order> findFilledByUser(UUID userId, LocalDate from, LocalDate to);

    // 전략 기준 기간 내 주문 전체 조회 (사용자 주문내역 탭용) — strategy_cycle을 통한 JOIN 필요
    List<Order> findByStrategyId(UUID strategyId, LocalDate from, LocalDate to);

    // 전략 기준 distinct 거래일 목록 조회 (관리자 주문 보정 거래일 드롭다운용)
    List<LocalDate> findTradeDatesByStrategyId(UUID strategyId);

    // AT_OPEN + PLANNED 주문 조회 (개장 스케쥴러·수동 실행 선접수용)
    List<Order> findAtOpenPlannedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate);

    // 사이클 기준 FILLED/PARTIALLY_FILLED BUY 체결금액 합계 (VR 전략 누적 매수금 계산용)
    BigDecimal sumFilledBuyAmountByCycleId(UUID strategyCycleId);

    // id 목록 일괄 조회 (DCA 회차 이력의 주문 조인용)
    List<Order> findAllByIds(Collection<UUID> ids);
}
