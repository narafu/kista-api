package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.AccountRequest;
import com.kista.adapter.in.web.dto.AccountResponse;
import com.kista.domain.port.in.DeleteAccountUseCase;
import com.kista.domain.port.in.GetAccountUseCase;
import com.kista.domain.port.in.KisConnectionTestUseCase;
import com.kista.domain.port.in.RegisterAccountUseCase;
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

import java.util.List;
import java.util.UUID;

@Tag(name = "계좌", description = "계좌 등록·조회·수정·삭제")
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final RegisterAccountUseCase registerAccount;
    private final UpdateAccountUseCase updateAccount;
    private final DeleteAccountUseCase deleteAccount;
    private final GetAccountUseCase getAccount;
    private final KisConnectionTestUseCase connectionTest; // KIS 자격증명 연결 테스트

    // 연결 테스트 요청/응답 DTO (컨트롤러 내부 전용)
    // accountId: 등록된 계좌 수정 시 전달하면 발급 토큰을 캐시에 저장 — 등록 전 사전 검증 시 null 허용
    record TestConnectionRequest(String appKey, String appSecret, UUID accountId) {}
    record TestConnectionResponse(boolean success, String message) {}

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
            @ApiResponse(responseCode = "400", description = "이미 등록된 계좌이거나 잘못된 요청"),
            @ApiResponse(responseCode = "422", description = "계좌번호가 KIS 자격증명과 일치하지 않음")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse register(@AuthenticationPrincipal UUID userId,
                                    @Valid @RequestBody AccountRequest request) {
        // 계좌번호 실소유 검증 — 불일치 시 InvalidKisKeyException → GlobalExceptionHandler → 422
        connectionTest.testAccountNo(request.kisAppKey(), request.kisSecretKey(), request.accountNo());
        return AccountResponse.from(
                registerAccount.register(userId, request.toRegisterCommand())
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
                updateAccount.update(id, userId, request.toUpdateCommand())
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
        deleteAccount.delete(id, userId);
    }

    // KIS API 자격증명 연결 테스트 — 토큰 발급 시도로 appKey/appSecret 유효성 확인
    @Operation(summary = "KIS API 연결 테스트", description = "appKey/appSecret으로 KIS OAuth 토큰 발급을 시도해 자격증명을 검증합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "검증 완료 (success 필드로 결과 확인)"),
    })
    @PostMapping("/connection-tests")
    public TestConnectionResponse testConnection(
            @AuthenticationPrincipal UUID userId,
            @RequestBody TestConnectionRequest request) {
        boolean success = connectionTest.test(request.appKey(), request.appSecret(), request.accountId());
        // 성공 시 message null, 실패 시 안내 메시지 반환
        String message = success ? null : "KIS API 인증에 실패했습니다. appKey 또는 appSecret을 확인하세요.";
        return new TestConnectionResponse(success, message);
    }
}
