package com.kista.tradingcore.stats.application.port.output;

import com.kista.tradingcore.stats.domain.model.DatedValue;

import java.util.List;

// 비트코인 네트워크 해시레이트 이력 포트
public interface HashrateHistoryPort {

    // 최근 2년 일별 해시레이트(EH/s, scale 2), 오래된 순
    List<DatedValue> dailyHashratesEh();
}
