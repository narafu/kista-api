package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.trading.application.event.OrderCancelFailedEvent;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.trading.domain.model.CancelResult;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.OrderCancelException;
import com.kista.tradingcore.trading.domain.model.OrderStateConflictException;
import com.kista.tradingcore.trading.domain.model.DstInfo;
import com.kista.tradingcore.broker.domain.model.BrokerApiException;
import com.kista.tradingcore.broker.domain.model.BrokerCredentialException;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.broker.domain.model.CancelInstruction;
import com.kista.tradingcore.broker.application.port.output.BrokerOrderCorrectionPort;
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
    private final SubmittingOrderAwaiter awaiter;     // 접수 중(SUBMITTING) 주문의 결과 확정 대기

    CancelResult cancelByCycle(UUID strategyId, UUID requesterId) {
        // 소유권 검증: 전략 → 계좌 → 요청자 일치 확인
        var strategy = strategyPort.findByIdOrThrow(strategyId);
        Account account = accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        return cancelOpenOrders(strategy.id(), account);
    }

    // 코인 거래소 시장가는 접수 즉시 체결돼 취소할 증권사 주문이 없다 — SUBMITTING·PLACED는 다음 10분 틱의 CryptoOrderSynchronizer가 확정한다.
    // 일시중지·삭제된 전략은 동기화 대상(활성 전략)에서 빠지므로, 확정 전에는 정리를 거부해 주문이 고아가 되지 않게 한다 — 호출자는 PAUSED 기록 전에 부른다
    void requireNoCryptoInFlight(UUID strategyId, Account account) {
        if (!account.broker().isCryptoExchange()) return;
        strategyCyclePort.findLatestByStrategyId(strategyId).ifPresent(cycle -> {
            boolean inFlight = orderPort.findOpenByAccountId(account.id()).stream()
                    .anyMatch(o -> cycle.id().equals(o.strategyCycleId()) && o.status() != OrderStatus.PLANNED);
            if (inFlight) {
                throw new OrderCancelException("체결 확인 중인 코인 주문이 있어 잠시 후 다시 시도해 주세요.");
            }
        });
    }

    // 전략의 현재 사이클 미체결 주문 정리 — 호출자가 소유권을 이미 검증한 경우(일시중지·삭제·계좌 삭제) 직접 사용
    CancelResult cancelOpenOrders(UUID strategyId, Account account) {
        // 현재 StrategyCycle 조회 — 사이클 단위로 취소 범위 격리, 사이클이 없으면 정리할 주문도 없다
        var latestCycle = strategyCyclePort.findLatestByStrategyId(strategyId);
        if (latestCycle.isEmpty()) {
            return new CancelResult(0, 0);
        }
        var currentCycle = latestCycle.get();
        // 코인 계좌는 아무것도 지우기 전에 체결 확인 중 주문을 거부한다
        requireNoCryptoInFlight(strategyId, account);

        // 오늘(KST)과 다음 거래일(수동 실행 tradeDate) 둘 다 — 04:00~장마감 리포트 사이 마감 배치가 접수한 오늘 주문도 포함
        List<LocalDate> tradeDates = Stream.of(LocalDate.now(TimeZones.KST), DstInfo.nextTradeDate()).distinct().toList();

        // PLANNED 주문 먼저 삭제 — 증권사 미접수이므로 DB만 처리
        int cancelledCount = 0;
        List<Order> placedOrders = new ArrayList<>();
        List<Order> stuckOrders = new ArrayList<>(); // 대기 후에도 접수 결과 미확정 — 증권사에 살아 있을 수 있어 재시도 가능한 실패
        for (LocalDate tradeDate : tradeDates) {
            // 실제 삭제 건수로 집계 — 그 사이 배치가 선점(SUBMITTING)·접수(PLACED)한 행은 삭제되지 않는다
            int plannedCount = stateWriter.deletePlanned(currentCycle.id(), tradeDate);
            if (plannedCount > 0) {
                log.info("PLANNED 주문 {}건 삭제 — cycleId={}, tradeDate={}", plannedCount, currentCycle.id(), tradeDate);
                cancelledCount += plannedCount;
            }
            // 코인 거래소은 증권사 취소 대상이 없다 — 체결 확인 중 주문은 위에서 이미 거부했으므로 PLANNED 삭제만 하고 넘어간다
            if (account.broker().isCryptoExchange()) {
                continue;
            }
            // 접수 중 주문은 결과가 정해질 때까지 기다린다 — PLACED가 된 건은 바로 아래 조회에 합류한다
            stuckOrders.addAll(awaiter.awaitCycle(currentCycle.id(), tradeDate));
            placedOrders.addAll(orderPort.findPlacedByCycleAndDate(currentCycle.id(), tradeDate));
        }

        // PLACED 주문: 증권사 취소 + DB 상태 변경 (best-effort)
        int failedCount = 0;
        int credentialFailedCount = 0; // 자격증명 오류 실패 — 재시도로 풀리지 않아 삭제를 막지 않는다
        List<String> failures = new ArrayList<>(); // 취소 실패 건 요약 — 커밋 후 알림 1건으로 통지
        for (Order stuck : stuckOrders) {
            log.warn("접수 결과 미확정 주문 — 취소 판정 보류: orderId={}", stuck.id());
            failures.add("orderId=" + stuck.id() + ": 접수 결과 미확정(SUBMITTING)");
            failedCount++;
        }

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
                .orElseThrow(() -> new NoSuchElementException("주문을 찾을 수 없습니다."));

        // 소유권 검증: 주문의 계좌가 요청자 소유인지 확인
        Account account = accountPort.requireOwnedAccount(order.accountId(), requesterId);

        if (order.status() != OrderStatus.PLANNED && account.broker().isCryptoExchange()) {
            // 코인 시장가는 즉시 체결돼 취소 대상이 아니다 — 주식 브로커 취소 경로로 보내지 않는다
            throw new OrderCancelException("코인 시장가 주문은 취소할 수 없습니다.");
        }
        if (order.status() == OrderStatus.PLANNED) {
            // 증권사 미접수 — DB에서만 취소 처리 (PLANNED일 때만 전이)
            try {
                stateWriter.cancelPlanned(orderId);
                return;
            } catch (OrderStateConflictException e) {
                // 읽은 뒤 배치가 접수(PLACED)했거나 정리됨 — 최신 상태로 다시 판정해 PLACED면 증권사 취소로 이어간다
                log.info("PLANNED 취소 경합 — 최신 상태로 재판정: orderId={}", orderId);
                order = orderPort.findById(orderId)
                        .orElseThrow(() -> new OrderCancelException("취소 가능한 상태가 아닙니다."));
            }
        }

        // 접수 중: 결과가 정해질 때까지 대기 — PLACED가 되면 아래 증권사 취소로 이어간다
        if (order.status() == OrderStatus.SUBMITTING) {
            order = awaiter.awaitOrder(order);
            if (order.status() == OrderStatus.SUBMITTING) {
                throw new OrderCancelException("주문 접수를 처리하는 중입니다. 잠시 후 다시 시도해 주세요.");
            }
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

