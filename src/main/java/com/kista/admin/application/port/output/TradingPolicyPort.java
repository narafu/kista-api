package com.kista.admin.application.port.output;

import com.kista.sharedkernel.TradingPolicySettings;

// trading-core가 소유하는 매매 런타임 정책(증권사 등록 허용·전략 생성 정책)의 조회·교체 — admin은 편집 UI로서 위임만 한다.
// 구현체는 내부 API(TradingPolicyHttpAdapter, root→trading-core 단방향).
public interface TradingPolicyPort {
    TradingPolicySettings load(); // 현재 정책 조회
    TradingPolicySettings replace(TradingPolicySettings settings); // 검증된 전체 정책으로 교체
}
