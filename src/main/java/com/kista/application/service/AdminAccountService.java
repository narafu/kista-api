package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.port.in.AdminListAccountsUseCase;
import com.kista.domain.port.out.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAccountService implements AdminListAccountsUseCase {

    private final AccountRepository accountRepository; // 전체 계좌 조회용

    @Override
    public List<Account> listAll() {
        return accountRepository.findAll();
    }
}
