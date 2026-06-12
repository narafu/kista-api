package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.AccountRequest;
import com.kista.adapter.in.web.dto.AccountResponse;
import com.kista.domain.model.account.Account;
import com.kista.domain.port.in.AccountUseCase;
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

import java.util.List;
import java.util.UUID;

@Tag(name = "계좌", description = "계좌 등록·조회·수정·삭제")
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountUseCase accountUseCase;

    // 연결 테스트 요청 DTO — accountId: 수정 시 전달하면 발급 토큰을 캐시에 저장, 등록 전 검증 시 null 허용
    record TestConnectionRequest(String appKey, String appSecret, UUID accountId) {}

    // 내 계좌 목록 조회 (민감정보 마스킹)
    @Operation(summary = "내 계좌 목록 조회", description = "로그인한 사용자의 전체 계좌 목록 반환. 계좌번호는 마지막 4자리만 노출.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    public List<AccountResponse> list(@AuthenticationPrincipal UUID userId) {
        return accountUseCase.listByUser(userId).stream()
                .map(AccountResponse::from)
                .toList();
    }

    // 계좌 등록 (AES-256 암호화 저장)
    @Operation(summary = "계좌 등록", description = "KIS/Toss 계좌 및 자격증명을 AES-256 암호화하여 저장.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "등록 성공"),
            @ApiResponse(responseCode = "400", description = "이미 등록된 계좌이거나 잘못된 요청"),
            @ApiResponse(responseCode = "422", description = "계좌번호가 자격증명과 일치하지 않음")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse register(@AuthenticationPrincipal UUID userId,
                                    @Valid @RequestBody AccountRequest request) {
        // KIS만 계좌번호 실소유 검증 — Toss는 AccountService.register() 내 testAndFetchAccountSeq()에서 통합 처리
        if (request.broker() == null || request.broker() == Account.Broker.KIS) {
            accountUseCase.testAccountNo(request.kisAppKey(), request.kisSecretKey(), request.accountNo());
        }
        return AccountResponse.from(
                accountUseCase.register(userId, request.toRegisterCommand())
        );
    }

    // 계좌 수정 (소유권 검증)
    @Operation(summary = "계좌 수정", description = "별명을 수정. 계좌번호 및 KIS 자격증명은 수정 불가.")
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
        return AccountResponse.from(
                accountUseCase.update(id, userId, request.toUpdateCommand())
        );
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
        accountUseCase.delete(id, userId);
    }

    // KIS API 자격증명 연결 테스트 — 실패 시 InvalidKisKeyException → GlobalExceptionHandler → 422
    @Operation(summary = "KIS API 연결 테스트", description = "appKey/appSecret으로 KIS OAuth 토큰 발급을 시도해 자격증명을 검증합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "연결 성공"),
            @ApiResponse(responseCode = "422", description = "appKey 또는 appSecret이 유효하지 않음"),
    })
    @PostMapping("/connection-tests")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void testConnection(
            @AuthenticationPrincipal UUID userId,
            @RequestBody TestConnectionRequest request) {
        // 실패 시 Account.InvalidKisKeyException → GlobalExceptionHandler → 422
        accountUseCase.test(request.appKey(), request.appSecret(), request.accountId());
    }
}
