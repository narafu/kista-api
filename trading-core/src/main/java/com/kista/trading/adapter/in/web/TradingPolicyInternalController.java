package com.kista.trading.adapter.in.web;

import com.kista.sharedkernel.TradingPolicySettings;
import com.kista.trading.application.usecase.TradingPolicyUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// root admin(TradingPolicyHttpAdapter)이 소비하는 내부 전용 정책 조회·교체 엔드포인트 — X-Internal-Token 인증.
// 정책 소유자는 이 프로세스(TradingPolicyService)이고 admin은 편집만 위임한다(root→trading-core 단방향).
// TradingPolicySettings는 sharedkernel 어휘라 요청/응답 body에 그대로 쓴다 — 생성자 검증이 enum 키 누락을 400으로 거절.
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/trading/policy-settings")
@RequiredArgsConstructor
public class TradingPolicyInternalController {

    private final TradingPolicyUseCase tradingPolicyUseCase;

    @Operation(summary = "매매 런타임 정책 조회", description = "증권사별 신규 등록 허용·전략 타입별 생성 정책. X-Internal-Token 필수.")
    @GetMapping
    public TradingPolicySettings getPolicy() {
        return tradingPolicyUseCase.getPolicy();
    }

    @Operation(summary = "매매 런타임 정책 전체 교체", description = "root admin PUT /api/admin/settings의 brokers/strategies 섹션 위임. X-Internal-Token 필수.")
    @PutMapping
    public TradingPolicySettings replacePolicy(@RequestBody TradingPolicySettings settings) {
        return tradingPolicyUseCase.replacePolicy(settings);
    }
}
