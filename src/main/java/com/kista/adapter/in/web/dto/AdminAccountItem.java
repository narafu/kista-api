package com.kista.adapter.in.web.dto;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.AccountNumberMasker;
import com.kista.domain.model.admin.AdminUserView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;
import java.util.UUID;

// 이상 징후 계좌 항목 DTO — AnomaliesResponse 내 pausedAccounts / inactiveAccounts 배열 원소
public record AdminAccountItem(
        @Schema(description = "계좌 고유 ID")
        UUID id,
        @Schema(description = "소유자 사용자 ID")
        UUID userId,
        @Schema(description = "소유자 닉네임 (사용자 조회 실패 시 \"(알 수 없음)\")")
        String ownerNickname,
        @Schema(description = "마스킹된 계좌번호", example = "****1234")
        String accountNoMasked,
        @Schema(description = "브로커 코드", example = "KIS")
        String broker
) {
    public static AdminAccountItem from(Account a, Map<UUID, AdminUserView> userMap) {
        AdminUserView user = a.userId() != null ? userMap.get(a.userId()) : null;
        String nickname = user != null ? user.nickname() : "(알 수 없음)";
        return new AdminAccountItem(
                a.id(), a.userId(), nickname, AccountNumberMasker.mask(a.accountNo()),
                a.broker() != null ? a.broker().name() : null);
    }
}
