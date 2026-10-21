package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.Quantities;
import com.kista.tradingcore.matching.domain.model.AccountBalance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

// 한 StrategyCycle 내의 포지션 스냅샷 — 체결마다 1건 append
// 최신 행 = 현재 보유 포지션
public record CyclePosition(
        UUID id,                  // PK (null이면 @UuidGenerator)
        UUID strategyCycleId,     // FK → strategy_cycle.id
        BigDecimal usdDeposit,    // 통합주문가능금액 (매매 공식 B 계산 기준)
        BigDecimal closingPrice,  // 종가
        BigDecimal avgPrice,      // 평균 매입 단가 (holdings=0이면 null)
        BigDecimal holdings,      // 보유 수량 (원장 — 소수 가능, 정규화됨)
        Instant createdAt,        // 생성 시각 (null이면 DB DEFAULT)
        Instant deletedAt         // soft-delete (null=활성)
) {
    public CyclePosition {
        holdings = Quantities.normalize(holdings); // 꼬리 0·지수 표기 제거
    }

    // 최초 전략 등록 시 시작점 포지션 (holdings=0, avgPrice=null, 종가 없음)
    public static CyclePosition initialSnapshot(UUID strategyCycleId, BigDecimal usdDeposit) {
        return new CyclePosition(null, strategyCycleId, usdDeposit, null, null, BigDecimal.ZERO, null, null);
    }

    // 사이클 재등록 시 시작점 포지션 (holdings=0, avgPrice=null)
    // closingPrice: 종료된 사이클의 장마감 가격
    public static CyclePosition cycleStartSnapshot(UUID strategyCycleId, BigDecimal usdDeposit, BigDecimal closingPrice) {
        return new CyclePosition(null, strategyCycleId, usdDeposit, closingPrice, null, BigDecimal.ZERO, null, null);
    }

    public static CyclePosition tradeSnapshot(UUID strategyCycleId, AccountBalance balance, BigDecimal closingPrice) {
        return new CyclePosition(null, strategyCycleId,
                balance.usdDeposit(), closingPrice, balance.avgPrice(), balance.holdings(), null, null);
    }

    // 중간부터 시작 — 기존 보유 수량·평단가로 seed하는 초기 포지션 (세 전략 공통)
    // closingPrice: 등록 시점 조회한 시장가(전일종가) — startAmount·VR V값과 동일 기준으로 정합해 등록 직후 미실현손익 0
    public static CyclePosition bootstrapSnapshot(UUID strategyCycleId, BigDecimal usdDeposit,
                                                    BigDecimal holdings, BigDecimal avgPrice, BigDecimal closingPrice) {
        return new CyclePosition(null, strategyCycleId, usdDeposit, closingPrice, avgPrice, holdings, null, null);
    }

    // DB 저장 포지션 → 매매 계산용 잔고 변환 (tradeSnapshot의 역방향)
    public AccountBalance toBalance() {
        return new AccountBalance(holdings, avgPrice, usdDeposit);
    }

    // 총자산(예수금 + 보유분 종가 평가) — 보유분이 있는데 종가가 없으면 평가 불완전이라 null(수익률 계산용, 평단가로 대체하지 않음), 반올림 없음
    public BigDecimal totalAssetAtCloseOrNull() {
        if (holdings.signum() > 0 && closingPrice == null) {
            return null;
        }
        BigDecimal unitPrice = closingPrice != null ? closingPrice : BigDecimal.ZERO;
        return usdDeposit.add(unitPrice.multiply(holdings));
    }

    // 총자산 — 종가 없으면 평단가, 그것도 없으면 0으로 대체해 항상 값 산출(화면 표시용), scale=2 HALF_UP
    public BigDecimal totalAssetWithAvgPriceFallback() {
        BigDecimal unitPrice = closingPrice != null ? closingPrice
                : avgPrice != null ? avgPrice : BigDecimal.ZERO;
        return usdDeposit.add(unitPrice.multiply(holdings))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
