package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.StrategyRequest;
import com.kista.adapter.in.web.dto.StrategyResponse;
import com.kista.domain.port.in.DeleteStrategyUseCase;
import com.kista.domain.port.in.GetStrategyUseCase;
import com.kista.domain.port.in.PauseStrategyUseCase;
import com.kista.domain.port.in.RegisterStrategyUseCase;
import com.kista.domain.port.in.ResumeStrategyUseCase;
import com.kista.domain.port.in.UpdateStrategyUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Tag(name = "전략", description = "계좌별 매매 전략 등록·조회·수정·삭제·중지·재개")
@RestController
@RequiredArgsConstructor
public class StrategyController {

    private final RegisterStrategyUseCase registerStrategy;
    private final UpdateStrategyUseCase updateStrategy;
    private final DeleteStrategyUseCase deleteStrategy;
    private final GetStrategyUseCase getStrategy;
    private final PauseStrategyUseCase pauseStrategy;
    private final ResumeStrategyUseCase resumeStrategy;

    // 계좌의 전략 목록 조회
    @Operation(summary = "전략 목록 조회")
    @GetMapping("/api/accounts/{accountId}/strategies")
    public List<StrategyResponse> list(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId) {
        return getStrategy.listByAccountId(accountId, userId).stream()
                .map(StrategyResponse::from)
                .toList();
    }

    // 전략 등록
    @Operation(summary = "전략 등록")
    @PostMapping("/api/accounts/{accountId}/strategies")
    @ResponseStatus(HttpStatus.CREATED)
    public StrategyResponse register(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody StrategyRequest request) {
        try {
            return StrategyResponse.from(
                    registerStrategy.register(userId, accountId, request.toRegisterCommand())
            );
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // 전략 수정 (ticker, multiple만 변경 가능)
    @Operation(summary = "전략 수정")
    @PutMapping("/api/strategies/{id}")
    public StrategyResponse update(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId,
            @RequestBody StrategyRequest request) {
        try {
            return StrategyResponse.from(
                    updateStrategy.update(id, userId, request.toUpdateCommand())
            );
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // 전략 삭제
    @Operation(summary = "전략 삭제")
    @DeleteMapping("/api/strategies/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        try {
            deleteStrategy.delete(id, userId);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // 전략 중지 (ACTIVE → PAUSED)
    @Operation(summary = "전략 중지")
    @PatchMapping("/api/strategies/{id}/pause")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pause(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        try {
            pauseStrategy.pause(id, userId);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // 전략 재개 (PAUSED → ACTIVE)
    @Operation(summary = "전략 재개")
    @PatchMapping("/api/strategies/{id}/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resume(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        try {
            resumeStrategy.resume(id, userId);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
