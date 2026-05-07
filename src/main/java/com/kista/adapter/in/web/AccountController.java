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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
    public List<AccountResponse> list() {
        return getAccount.listByUser(currentUserId()).stream()
                .map(AccountResponse::from)
                .toList();
    }

    // 계좌 등록 (AES-256 암호화 저장)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse register(@RequestBody AccountRequest request) {
        try {
            return AccountResponse.from(
                    registerAccount.register(currentUserId(), request.toRegisterCommand())
            );
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // 계좌 수정 (소유권 검증)
    @PutMapping("/{id}")
    public AccountResponse update(@PathVariable UUID id, @RequestBody AccountRequest request) {
        try {
            return AccountResponse.from(
                    updateAccount.update(id, currentUserId(), request.toUpdateCommand())
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
    public void delete(@PathVariable UUID id) {
        try {
            deleteAccount.delete(id, currentUserId());
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // 전략 중지 (ACTIVE → PAUSED)
    @PatchMapping("/{id}/strategy/pause")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pauseStrategy(@PathVariable UUID id) {
        try {
            pauseStrategy.pause(id, currentUserId());
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // 전략 재개 (PAUSED → ACTIVE)
    @PatchMapping("/{id}/strategy/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resumeStrategy(@PathVariable UUID id) {
        try {
            resumeStrategy.resume(id, currentUserId());
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    private UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return UUID.fromString((String) auth.getPrincipal());
    }
}
