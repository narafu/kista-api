package com.kista.trading.adapter.in.web;

import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.trading.application.port.output.StrategyPort;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// user.ActiveStrategyCountPort 구현체(web.trading.ActiveStrategyCountAdapter)가 소비하는
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
        return accountPort.findByUserId(userId).stream()
                .map(Account::id)
                .flatMap(accountId -> strategyPort.findByAccountId(accountId).stream())
                .filter(strategy -> strategy.isActive())
                .count();
    }
}
