package com.kista.trading.adapter.in.web;

import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.trading.application.usecase.StrategyUseCase;
import com.kista.trading.domain.model.Strategy;
import com.kista.sharedkernel.StrategyStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

// admin의 pauseStrategy/resumeStrategy가 소유권 검증(strategy.accountId == account.id)까지
// 포함해 위임하는 내부 전용 엔드포인트 — Account/Strategy를 root에 노출하지 않기 위함
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/trading/accounts/{accountId}/strategies/{strategyId}")
@RequiredArgsConstructor
public class StrategyStatusInternalController {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyUseCase strategyUseCase; // 사용자 재개와 동일 경로 — 종료된(좀비) 사이클 재오픈 포함

    @Operation(summary = "전략 상태 변경(일시정지/재개)", description = "관리자 일시정지/재개 전용. 소유권 불일치 시 400. X-Internal-Token 필수.")
    @PatchMapping("/status")
    public void updateStatus(@PathVariable UUID accountId, @PathVariable UUID strategyId,
                              @RequestParam StrategyStatus status) {
        Account account = accountPort.findByIdOrThrow(accountId);
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        if (!strategy.accountId().equals(account.id())) {
            throw new IllegalArgumentException("strategy가 account에 속하지 않습니다");
        }
        if (strategy.status() == status) return; // 이미 같은 상태 — 기존 동작대로 no-op
        // status만 저장하면 resume 시 종료된 사이클이 재오픈되지 않아 좀비 상태가 유지된다
        switch (status) {
            case ACTIVE -> strategyUseCase.resume(strategyId, account.userId());
            case PAUSED -> strategyUseCase.pause(strategyId, account.userId());
        }
    }
}
