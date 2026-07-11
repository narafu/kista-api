package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.AdminAccountResponse;
import com.kista.adapter.in.web.dto.AdminStrategyResponse;
import com.kista.adapter.in.web.dto.StrategyStatusRequest;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.in.AdminQueryUseCase;
import com.kista.domain.port.in.AdminStrategyUseCase;
import com.kista.domain.port.in.AdminUserUseCase;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/accounts")
@RequiredArgsConstructor
public class AdminAccountController {

    private final AdminQueryUseCase adminQuery;  // 계좌 목록 조회
    private final AdminUserUseCase adminUser;    // ownerNickname 조회용 사용자 목록
    private final AdminStrategyUseCase adminStrategy; // 관리자 전략 상태 변경

    @GetMapping
    public List<AdminAccountResponse> listAccounts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Map<UUID, AdminUserView> userMap = AdminUserViews.mapById(adminUser);
        List<Account> accounts = adminQuery.listAccounts(from, to);
        Set<UUID> accountIds = accounts.stream().map(Account::id).collect(Collectors.toSet());
        Map<UUID, List<Strategy>> strategyMap = adminQuery.listStrategiesByAccountIds(accountIds);
        return accounts.stream()
                .map(a -> AdminAccountResponse.from(a, userMap.get(a.userId()), strategyMap.getOrDefault(a.id(), List.of())))
                .toList();
    }

    // 계좌 선택 이후 전략 선택 드롭다운용 목록
    @GetMapping("/{accountId}/strategies")
    public List<AdminStrategyResponse> listStrategies(@PathVariable UUID accountId) {
        return adminQuery.listStrategies(accountId).stream()
                .map(AdminStrategyResponse::from)
                .toList();
    }

    @PatchMapping("/{accountId}/strategies/{strategyId}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateStrategyStatus(
            @PathVariable UUID accountId,
            @PathVariable UUID strategyId,
            @RequestBody StrategyStatusRequest body,
            @AuthenticationPrincipal UUID adminId) {
        switch (body.status()) {
            case ACTIVE -> adminStrategy.resumeStrategy(adminId, accountId, strategyId);
            case PAUSED -> adminStrategy.pauseStrategy(adminId, accountId, strategyId);
            default -> throw new IllegalArgumentException("허용되지 않는 status: " + body.status());
        }
    }

}
