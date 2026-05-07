package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.AccountRequest;
import com.kista.adapter.in.web.dto.AccountResponse;
import com.kista.domain.port.in.DeleteAccountUseCase;
import com.kista.domain.port.in.GetAccountUseCase;
import com.kista.domain.port.in.PauseStrategyUseCase;
import com.kista.domain.port.in.RegisterAccountUseCase;
import com.kista.domain.port.in.ResumeStrategyUseCase;
import com.kista.domain.port.in.UpdateAccountUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final RegisterAccountUseCase registerAccount;
    private final UpdateAccountUseCase updateAccount;
    private final DeleteAccountUseCase deleteAccount;
    private final GetAccountUseCase getAccount;
    private final PauseStrategyUseCase pauseStrategy;
    private final ResumeStrategyUseCase resumeStrategy;

    // 내 계좌 목록 조회 (민감정보 마스킹)
    @GetMapping
    public List<AccountResponse> list(@AuthenticationPrincipal UUID userId) {
        return getAccount.listByUser(userId).stream()
                .map(AccountResponse::from)
                .toList();
    }

    // 계좌 등록 (AES-256 암호화 저장)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse register(@AuthenticationPrincipal UUID userId,
                                    @RequestBody AccountRequest request) {
        try {
            return AccountResponse.from(
                    registerAccount.register(userId, request.toRegisterCommand())
            );
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // 계좌 수정 (소유권 검증)
    @PutMapping("/{id}")
    public AccountResponse update(@PathVariable UUID id,
                                  @AuthenticationPrincipal UUID userId,
                                  @RequestBody AccountRequest request) {
        try {
            return AccountResponse.from(
                    updateAccount.update(id, userId, request.toUpdateCommand())
            );
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // 계좌 삭제 (소유권 검증)
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal UUID userId) {
        try {
            deleteAccount.delete(id, userId);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // 전략 중지 (ACTIVE → PAUSED)
    @PatchMapping("/{id}/strategy/pause")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pauseStrategy(@PathVariable UUID id, @AuthenticationPrincipal UUID userId) {
        try {
            pauseStrategy.pause(id, userId);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // 전략 재개 (PAUSED → ACTIVE)
    @PatchMapping("/{id}/strategy/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resumeStrategy(@PathVariable UUID id, @AuthenticationPrincipal UUID userId) {
        try {
            resumeStrategy.resume(id, userId);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
