package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.order.NextOrdersPreview;
import com.kista.domain.model.order.NextOrdersPreview.SkipReason;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.PrivacyTradePort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kista.domain.model.strategy.DstInfo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class TradingPreviewService {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final BrokerPriceRouter brokerPriceRouter;
    private final PrivacyTradePort privacyTradePort;
    private final TradingBalanceLoader balanceLoader;
    private final CycleOrderComputer orderComputer;

    // execute()와 동일한 잔고 출처(CyclePosition) 및 전략 분기로 미리보기
    // 휴장 여부는 무시하고 항상 강제 계산 — DB 저장 없음
    @Transactional(readOnly = true)
    NextOrdersPreview preview(UUID strategyId, UUID requesterId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        Account account = accountPort.findByIdOrThrow(strategy.accountId());
        account.verifyOwnedBy(requesterId);

        // 현재 StrategyCycle — initialUsdDeposit 조회 (PRIVACY에서 필요)
        StrategyCycle currentCycle = strategyCyclePort.findLatestByStrategyId(strategy.id())
                .orElseThrow(() -> new NoSuchElementException("활성 사이클 없음: strategyId=" + strategy.id()));

        // 스케줄러는 KST 04:00에 실행 — 04:00 이후 미리보기는 내일 매매 기준
        LocalDate today = DstInfo.nextTradeDate();

        // 잔고 로드 (preview 전용 — 이력 없음도 정상 skip으로 처리)
        TradingBalanceLoader.BalanceLoad load = balanceLoader.tryLoadBalance(strategy);
        if (load.isSkip()) {
            return new NextOrdersPreview(today, null, List.of(), load.skipReason());
        }
        AccountBalance balance = load.balance();

        // INFINITE은 전일종가 필요(0회차 평단가 대용), PRIVACY는 기준매매표 필요 — 전략 입력 컨텍스트로 통합
        BigDecimal prevClosePrice = strategy.type() == Strategy.Type.INFINITE
                ? brokerPriceRouter.getPriceSnapshot(strategy.ticker(), account).prevClose()
                : null;
        PrivacyTradeBase privacyBase = strategy.type() == Strategy.Type.PRIVACY
                ? privacyTradePort.findTodayTrade(today).orElse(null)
                : null;

        CycleOrderComputer.ComputeResult result = orderComputer.compute(
                balance, strategy, prevClosePrice, today, currentCycle, privacyBase, "preview:" + strategyId);

        // 전략 차원 skip — 현재 케이스는 PRIVACY 기준매매표 미수신만 해당
        if (result.isSkipped()) {
            return new NextOrdersPreview(today, null, List.of(), SkipReason.NO_PRIVACY_BASE);
        }

        // 주문 유효성: 매수금액 > 잔액 or 매도수량 > 보유수량이면 skip
        // position 포함 — 단위금액·현재가 정보를 프론트에 전달하기 위해 (INFINITE만 non-null)
        if (!result.valid()) {
            return new NextOrdersPreview(today, result.position(), List.of(), SkipReason.INSUFFICIENT_BALANCE);
        }
        return new NextOrdersPreview(today, result.position(), result.orders(), null);
    }
}
