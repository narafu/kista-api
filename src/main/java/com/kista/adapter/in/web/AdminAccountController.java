package com.kista.adapter.in.web;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.AdminListAccountsUseCase;
import com.kista.domain.port.in.AdminListUsersUseCase;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Tag(name = "Admin - Accounts", description = "관리자 계좌 현황 API")
@RestController
@RequestMapping("/api/admin/accounts")
@RequiredArgsConstructor
public class AdminAccountController {

    private final AdminListAccountsUseCase listAccounts;
    private final AdminListUsersUseCase listUsers; // ownerNickname 조회용 사용자 목록

    @GetMapping
    public List<AdminAccountResponse> listAccounts() {
        // 사용자 맵 빌드 (userId → User) — N+1 방지 일괄 조회
        Map<UUID, User> userMap = listUsers.listAll().stream()
                .collect(Collectors.toMap(User::id, Function.identity()));
        return listAccounts.listAll().stream()
                .map(a -> AdminAccountResponse.from(a, userMap.get(a.userId())))
                .toList();
    }

    // 계좌 목록 응답 DTO
    record AdminAccountResponse(
            UUID id,
            UUID userId,
            String ownerNickname,     // User.nickname
            String accountNoMasked,   // "****1234"
            String ticker,            // Ticker.name()
            String strategyType,      // StrategyType.name()
            String strategyStatus     // StrategyStatus.name()
    ) {
        static AdminAccountResponse from(Account a, User user) {
            String nickname = user != null ? user.nickname() : "(알 수 없음)";
            String masked = "****" + a.accountNo().substring(
                    Math.max(0, a.accountNo().length() - 4));
            return new AdminAccountResponse(
                    a.id(), a.userId(), nickname, masked,
                    a.ticker().name(), a.strategyType().name(), a.strategyStatus().name());
        }
    }
}
