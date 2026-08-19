package com.kista.application.service.finance;

import com.kista.domain.model.finance.FinanceAccount;
import com.kista.domain.model.finance.FinanceAccountCommand;
import com.kista.domain.port.in.FinanceAccountUseCase;
import com.kista.domain.port.out.FinanceAccountPort;
import com.kista.domain.port.out.FinanceGroupPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class FinanceAccountService implements FinanceAccountUseCase {

    private final FinanceAccountPort accountPort;
    private final FinanceGroupPort financeGroupPort;

    @Override
    @Transactional(readOnly = true)
    public List<FinanceAccount> list(UUID userId, UUID requestedGroupId) {
        UUID groupId = financeGroupPort.resolveGroupId(userId, requestedGroupId);
        return accountPort.findByGroupId(groupId);
    }

    @Override
    public FinanceAccount create(UUID userId, UUID requestedGroupId, FinanceAccountCommand command) {
        UUID groupId = financeGroupPort.resolveGroupId(userId, requestedGroupId);
        FinanceAccount account = new FinanceAccount(null, groupId, userId, command.accountType(),
                command.name(), command.accountNo(), command.memo(), null);
        FinanceAccount saved = accountPort.save(account);
        log.info("계좌 등록: groupId={}, accountId={}", groupId, saved.id());
        return saved;
    }

    @Override
    public FinanceAccount update(UUID accountId, UUID userId, FinanceAccountCommand command) {
        FinanceAccount existing = accountPort.findByIdOrThrow(accountId);
        financeGroupPort.resolveGroupId(userId, existing.groupId());
        FinanceAccount updated = new FinanceAccount(existing.id(), existing.groupId(), existing.createdBy(),
                command.accountType(), command.name(), command.accountNo(), command.memo(), existing.createdAt());
        return accountPort.save(updated);
    }

    @Override
    public void delete(UUID accountId, UUID userId) {
        FinanceAccount existing = accountPort.findByIdOrThrow(accountId);
        financeGroupPort.resolveGroupId(userId, existing.groupId());
        accountPort.softDelete(accountId);
        log.info("계좌 삭제: accountId={}, userId={}", accountId, userId);
    }
}
