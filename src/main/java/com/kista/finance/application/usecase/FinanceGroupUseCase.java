package com.kista.finance.application.usecase;

import com.kista.finance.domain.model.FinanceGroup;
import com.kista.finance.domain.model.FinanceGroupInvitation;
import com.kista.finance.domain.model.FinanceGroupMember;

import java.util.List;
import java.util.UUID;

public interface FinanceGroupUseCase {
    List<FinanceGroup> listMyGroups(UUID userId);
    List<FinanceGroupMember> listMembers(UUID groupId, UUID userId);
    FinanceGroupInvitation invite(UUID groupId, UUID userId, long expiresInHours);
    FinanceGroupInvitation respondToInvitation(String code, UUID userId, FinanceGroupInvitation.Status status);
    void leaveGroup(UUID groupId, UUID userId, UUID targetUserId);
}
