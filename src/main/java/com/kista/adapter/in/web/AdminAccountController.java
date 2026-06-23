package com.kista.adapter.in.web;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.port.in.AdminQueryUseCase;
import com.kista.domain.port.in.AdminUserUseCase;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/accounts")
@RequiredArgsConstructor
public class AdminAccountController {

    private final AdminQueryUseCase adminQuery;  // 계좌 목록 조회
    private final AdminUserUseCase adminUser;    // ownerNickname 조회용 사용자 목록

    @GetMapping
    public List<AdminAccountResponse> listAccounts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Map<UUID, AdminUserView> userMap = adminUser.listAll(null, null).stream()
                .collect(Collectors.toMap(AdminUserView::id, Function.identity()));
        return adminQuery.listAccounts(from, to).stream()
                .map(a -> AdminAccountResponse.from(a, userMap.get(a.userId())))
                .toList();
    }

    // 계좌 목록 응답 DTO
    record AdminAccountResponse(
            UUID id,
            UUID userId,
            String ownerNickname,   // User.nickname
            String accountNoMasked, // "****1234"
            String broker           // Broker.name()
    ) {
        static AdminAccountResponse from(Account a, AdminUserView user) {
            String nickname = user != null ? user.nickname() : "(알 수 없음)";
            String masked = "****" + a.accountNo().substring(
                    Math.max(0, a.accountNo().length() - 4));
            return new AdminAccountResponse(
                    a.id(), a.userId(), nickname, masked,
                    a.broker() != null ? a.broker().name() : null);
        }
    }
}
