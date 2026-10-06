package com.kista.trading.application.service;

import com.kista.sharedkernel.OrderStatus;
import com.kista.sharedkernel.TimeZones;
import com.kista.trading.application.event.OrderCancelFailedEvent;
import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.CancelResult;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.OrderCancelException;
import com.kista.trading.domain.model.DstInfo;
import com.kista.broker.domain.model.BrokerApiException;
import com.kista.broker.domain.model.BrokerCredentialException;
import com.kista.account.application.port.output.AccountPort;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.broker.domain.model.CancelInstruction;
import com.kista.broker.application.port.output.BrokerOrderCorrectionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Stream;

// 비-트랜잭션 서비스 — 브로커 취소 HTTP는 트랜잭션 밖에서 실행, DB 상태변경만 OrderCancelStateWriter의 짧은 트랜잭션으로 위임
@Slf4j
@Service
@RequiredArgsConstructor
class OrderCancelService {

    private final OrderPort orderPort;
    private final BrokerOrderCorrectionPort brokerOrderCorrectionPort;
    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final ApplicationEventPublisher eventPublisher; // 취소 실패 관리자 알림 — 트랜잭션 내부에서 텔레그램 직접 호출 금지, 커밋 후 이벤트로 위임
    private final OrderCancelStateWriter stateWriter; // self-invocation 프록시 미경유 문제 회피 — DB 쓰기 전용 별도 빈

    CancelResult cancelByCycle(UUID strategyId, UUID requesterId) {
        // 소유권 검증: 전략 → 계좌 → 요청자 일치 확인
        var strategy = strategyPort.findByIdOrThrow(strategyId);
        Account account = accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        return cancelOpenOrders(strategy.id(), account);
    }

    // 전략의 현재 사이클 미체결 주문 정리 — 호출자가 소유권을 이미 검증한 경우(일시정지·삭제·계좌 삭제) 직접 사용
    CancelResult cancelOpenOrders(UUID strategyId, Account account) {
        // 현재 StrategyCycle 조회 — 사이클 단위로 취소 범위 격리, 사이클이 없으면 정리할 주문도 없다
        var latestCycle = strategyCyclePort.findLatestByStrategyId(strategyId);
        if (latestCycle.isEmpty()) {
            return new CancelResult(0, 0);
        }
        var currentCycle = latestCycle.get();

        // 오늘(KST)과 다음 거래일(수동 실행 tradeDate) 둘 다 — 04:00~장마감 리포트 사이 마감 배치가 접수한 오늘 주문도 포함
        List<LocalDate> tradeDates = Stream.of(LocalDate.now(TimeZones.KST), DstInfo.nextTradeDate()).distinct().toList();

        // PLANNED 주문 먼저 삭제 — 증권사 미접수이므로 DB만 처리
        int cancelledCount = 0;
        List<Order> placedOrders = new ArrayList<>();
        for (LocalDate tradeDate : tradeDates) {
            int plannedCount = orderPort.findPlannedByCycleAndDate(currentCycle.id(), tradeDate).size();
            if (plannedCount > 0) {
                stateWriter.deletePlanned(currentCycle.id(), tradeDate);
                log.info("PLANNED 주문 {}건 삭제 — cycleId={}, tradeDate={}", plannedCount, currentCycle.id(), tradeDate);
                cancelledCount += plannedCount;
            }
            placedOrders.addAll(orderPort.findPlacedByCycleAndDate(currentCycle.id(), tradeDate));
        }

        // PLACED 주문: 증권사 취소 + DB 상태 변경 (best-effort)
        int failedCount = 0;
        int credentialFailedCount = 0; // 자격증명 오류 실패 — 재시도로 풀리지 않아 삭제를 막지 않는다
        List<String> failures = new ArrayList<>(); // 취소 실패 건 요약 — 커밋 후 알림 1건으로 통지

        for (Order order : placedOrders) {
            try {
                brokerOrderCorrectionPort
                        .cancel(new CancelInstruction(order.ticker(), order.externalOrderId()), account.toBrokerRef());
                stateWriter.markCancelled(order.id());
                cancelledCount++;
            } catch (Exception e) {
                if (isAlreadyCanceled(e)) {
                    // 동일 사이클에 대한 중복 취소 요청(경쟁 상태)의 예상된 결과 — 이미 취소된 상태이므로 성공으로 흡수
                    log.info("주문이 이미 취소됨 — orderId={}, externalOrderId={}", order.id(), order.externalOrderId());
                    stateWriter.markCancelled(order.id());
                    cancelledCount++;
                    continue;
                }
                log.warn("주문 취소 실패 — orderId={}, externalOrderId={}: {}",
                        order.id(), order.externalOrderId(), e.getMessage());
                failures.add("orderId=" + order.id() + ", externalOrderId=" + order.externalOrderId()
                        + ": " + e.getMessage());
                failedCount++;
                if (e instanceof BrokerCredentialException) {
                    credentialFailedCount++;
                }
            }
        }

        // best-effort 실패도 관리자가 인지할 수 있도록 알림 — 트랜잭션 커밋 후 발행(@Transactional 내부 외부 API 호출 금지)
        if (!failures.isEmpty()) {
            eventPublisher.publishEvent(new OrderCancelFailedEvent(strategyId, failedCount, String.join("; ", failures)));
        }

        return new CancelResult(cancelledCount, failedCount, credentialFailedCount);
    }

    void cancelOrder(UUID orderId, UUID requesterId) {
        Order order = orderPort.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));

        // 소유권 검증: 주문의 계좌가 요청자 소유인지 확인
        Account account = accountPort.requireOwnedAccount(order.accountId(), requesterId);

        if (order.status() == OrderStatus.PLANNED) {
            // 증권사 미접수 — DB에서만 취소 처리
            stateWriter.markCancelled(orderId);
            return;
        }

        // PLACED 상태: 증권사 취소 후 DB 상태 변경
        if (order.status() != OrderStatus.PLACED) {
            log.info("취소 불가 상태 주문 취소 요청: orderId={}, status={}", orderId, order.status());
            throw new OrderCancelException("취소 가능한 상태가 아닙니다.");
        }

        try {
            brokerOrderCorrectionPort
                    .cancel(new CancelInstruction(order.ticker(), order.externalOrderId()), account.toBrokerRef());
        } catch (Exception e) {
            if (!isAlreadyCanceled(e)) {
                throw e;
            }
            // 동일 주문에 대한 중복 취소 요청(경쟁 상태)의 예상된 결과 — 이미 취소된 상태이므로 성공으로 흡수
            log.info("주문이 이미 취소됨 — orderId={}, externalOrderId={}", orderId, order.externalOrderId());
        }
        stateWriter.markCancelled(orderId);
    }

    // 중복 취소 요청으로 브로커가 거부한 예상된 경합 여부 — TossHttpClient가 409 CONFLICT(already-canceled) 응답을 판정해 전달
    private boolean isAlreadyCanceled(Exception e) {
        return e instanceof BrokerApiException bae && bae.isAlreadyCanceledConflict();
    }

}

