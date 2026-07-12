package com.kista.adapter.in.web.dto;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.AccountNumberMasker;
import com.kista.domain.model.admin.AdminUserView;

import java.util.Map;
import java.util.UUID;

// 이상 징후 계좌 항목 DTO — AnomaliesResponse 내 pausedAccounts / inactiveAccounts 배열 원소
public record AdminAccountItem(
        UUID id,
        UUID userId,
        String ownerNickname,
        String accountNoMasked,
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
