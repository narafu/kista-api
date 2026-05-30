package com.kista.adapter.in.web;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.admin.AdminAnomalies;
import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.port.in.AdminAnomaliesUseCase;
import com.kista.domain.port.in.AdminListUsersUseCase;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/anomalies")
@RequiredArgsConstructor
public class AdminAnomaliesController {

    private final AdminAnomaliesUseCase anomaliesUseCase;
    private final AdminListUsersUseCase listUsers;

    @GetMapping
    public AdminAnomaliesResponse getAnomalies() {
        AdminAnomalies anomalies = anomaliesUseCase.getAnomalies();

        Map<UUID, AdminUserView> userMap = listUsers.listAll().stream()
                .collect(Collectors.toMap(AdminUserView::id, Function.identity()));

        List<AccountItem> pausedAccounts = anomalies.pausedAccounts().stream()
                .map(a -> AccountItem.from(a, userMap))
                .toList();

        List<AccountItem> inactiveAccounts = anomalies.inactiveAccounts().stream()
                .map(a -> AccountItem.from(a, userMap))
                .toList();

        return new AdminAnomaliesResponse(pausedAccounts, inactiveAccounts);
    }

    record AdminAnomaliesResponse(
            List<AccountItem> pausedAccounts,
            List<AccountItem> inactiveAccounts
    ) {}

    record AccountItem(
            UUID id,
            UUID userId,
            String ownerNickname,
            String accountNoMasked,
            String broker
    ) {
        static AccountItem from(Account a, Map<UUID, AdminUserView> userMap) {
            AdminUserView user = a.userId() != null ? userMap.get(a.userId()) : null;
            String nickname = user != null ? user.nickname() : "(알 수 없음)";
            String masked = a.accountNo() != null
                    ? "****" + a.accountNo().substring(Math.max(0, a.accountNo().length() - 4))
                    : "****";
            return new AccountItem(
                    a.id(), a.userId(), nickname, masked,
                    a.broker() != null ? a.broker().name() : null);
        }
    }
}
