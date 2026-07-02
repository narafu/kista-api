package com.kista.adapter.in.web.dto;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.model.strategy.Strategy;

import java.util.List;
import java.util.UUID;

// 관리자 계좌 목록 응답 DTO — 소유자 닉네임 + 마스킹된 계좌번호 포함
public record AdminAccountResponse(
        UUID id,
        UUID userId,
        String ownerNickname,   // User.nickname
        String accountNoMasked, // "****1234"
        String broker,          // Broker.name()
        List<AdminStrategyResponse> strategies
) {
    public static AdminAccountResponse from(Account a, AdminUserView user, List<Strategy> strategies) {
        String nickname = user != null ? user.nickname() : "(알 수 없음)";
        String masked = "****" + a.accountNo().substring(
                Math.max(0, a.accountNo().length() - 4));
        return new AdminAccountResponse(
                a.id(), a.userId(), nickname, masked,
                a.broker() != null ? a.broker().name() : null,
                strategies.stream().map(AdminStrategyResponse::from).toList());
    }
}
