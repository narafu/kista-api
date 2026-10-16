package com.kista.tradingcore.trading.adapter.in.web;

import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.domain.model.Strategy;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// user.ActiveStrategyCountPort 구현체(user.adapter.out.internal.ActiveStrategyCountAdapter)가 소비하는
// 내부 전용 엔드포인트 — account/trading 타입을 root에 노출하지 않기 위함
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/trading")
@RequiredArgsConstructor
public class ActiveStrategyCountInternalController {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;

    @GetMapping("/active-strategy-count")
    public long activeStrategyCount(@RequestParam UUID userId) {
        // 계좌별 조회 대신 배치 1회 (N+1 방지)
        List<UUID> accountIds = accountPort.findByUserId(userId).stream().map(Account::id).toList();
        if (accountIds.isEmpty()) return 0;
        return strategyPort.findByAccountIds(accountIds).values().stream()
                .flatMap(List::stream)
                .filter(Strategy::isActive)
                .count();
    }
}
