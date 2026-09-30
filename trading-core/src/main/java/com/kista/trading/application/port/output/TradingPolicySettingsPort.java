package com.kista.trading.application.port.output;

import com.kista.sharedkernel.TradingPolicySettings;

// 매매 런타임 정책(증권사 등록 허용·전략 생성 정책) 영속화 — trading 스키마 단일 행(jsonb).
public interface TradingPolicySettingsPort {
    TradingPolicySettings load(); // 현재 정책 조회 — 행이 없으면 defaults()
    TradingPolicySettings save(TradingPolicySettings settings); // 전체 교체 저장
}
