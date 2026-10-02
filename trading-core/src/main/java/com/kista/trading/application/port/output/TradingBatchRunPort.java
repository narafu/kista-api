package com.kista.trading.application.port.output;

import com.kista.trading.domain.model.TradingBatchJob;
import com.kista.trading.domain.model.TradingBatchPhase;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

// 매매 배치 재개 체크포인트 저장소 — 단계 기록 + 전략별 리포트 완료 마커
public interface TradingBatchRunPort {
    // (job, 거래일) 마지막 기록 단계 — 실행 이력 없으면 empty
    Optional<TradingBatchPhase> findPhase(TradingBatchJob job, LocalDate tradeDate);

    // 단계 upsert
    void recordPhase(TradingBatchJob job, LocalDate tradeDate, TradingBatchPhase phase);

    // 전략의 당일 리포트(cycle_position 저장) 완료 여부
    boolean isReported(LocalDate tradeDate, UUID strategyId);

    // 리포트 완료 마커 기록 — 중복 호출 무해
    void markReported(LocalDate tradeDate, UUID strategyId);
}
