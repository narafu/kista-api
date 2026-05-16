package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.AccountRequest;
import com.kista.adapter.in.web.dto.AccountResponse;
import com.kista.domain.model.InvalidKisKeyException;
import com.kista.domain.port.in.DeleteAccountUseCase;
import com.kista.domain.port.in.GetAccountUseCase;
import com.kista.domain.port.in.PauseStrategyUseCase;
import com.kista.domain.port.in.RegisterAccountUseCase;
import com.kista.domain.port.in.ResumeStrategyUseCase;
import com.kista.domain.port.in.UpdateAccountUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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

@Tag(name = "계좌", description = "KIS 계좌 등록·조회·수정·삭제 및 전략 제어")
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
    @Operation(summary = "내 계좌 목록 조회", description = "로그인한 사용자의 전체 계좌 목록 반환. 계좌번호는 마지막 4자리만 노출.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    public List<AccountResponse> list(@AuthenticationPrincipal UUID userId) {
        return getAccount.listByUser(userId).stream()
                .map(AccountResponse::from)
                .toList();
    }

    // 계좌 등록 (AES-256 암호화 저장)
    @Operation(summary = "계좌 등록", description = "KIS 계좌 및 자격증명을 AES-256 암호화하여 저장.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "등록 성공"),
            @ApiResponse(responseCode = "400", description = "이미 등록된 계좌이거나 잘못된 요청")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse register(@AuthenticationPrincipal UUID userId,
                                    @Valid @RequestBody AccountRequest request) {
        try {
            return AccountResponse.from(
                    registerAccount.register(userId, request.toRegisterCommand())
            );
        } catch (InvalidKisKeyException e) {
            // KIS 앱키/시크릿 유효성 검증 실패 → 422 Unprocessable Entity
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // 계좌 수정 (소유권 검증)
    @Operation(summary = "계좌 수정", description = "별명, KIS 자격증명, 텔레그램 설정, 종목 등을 수정. 계좌번호·전략종류는 수정 불가.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음")
    })
    @PutMapping("/{id}")
    public AccountResponse update(
            @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @PathVariable UUID id,
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
    @Operation(summary = "계좌 삭제", description = "계좌 및 관련 데이터를 영구 삭제.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음")
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        try {
            deleteAccount.delete(id, userId);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // 전략 중지 (ACTIVE → PAUSED)
    @Operation(summary = "전략 중지", description = "매매 전략을 ACTIVE → PAUSED 상태로 전환. 다음 스케줄부터 매매 실행 안 함.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "중지 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음")
    })
    @PatchMapping("/{id}/strategy/pause")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pauseStrategy(
            @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
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
    @Operation(summary = "전략 재개", description = "매매 전략을 PAUSED → ACTIVE 상태로 전환. 다음 스케줄부터 매매 실행 재개.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "재개 성공"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음")
    })
    @PatchMapping("/{id}/strategy/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resumeStrategy(
            @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
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
