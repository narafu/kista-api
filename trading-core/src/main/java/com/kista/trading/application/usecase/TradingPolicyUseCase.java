package com.kista.trading.application.usecase;

import com.kista.sharedkernel.TradingPolicySettings;

// 매매 런타임 정책 조회·교체 — root admin이 내부 API(TradingPolicyInternalController)로 위임 호출한다.
public interface TradingPolicyUseCase {
    TradingPolicySettings getPolicy(); // 현재 정책 조회 (행이 없으면 기본값)
    TradingPolicySettings replacePolicy(TradingPolicySettings settings); // 검증된 전체 정책으로 교체
}
