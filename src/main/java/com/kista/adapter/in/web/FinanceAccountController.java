package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.FinanceAccountRequest;
import com.kista.adapter.in.web.dto.FinanceAccountResponse;
import com.kista.domain.port.in.FinanceAccountUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Tag(name = "재무")
@RestController
@RequestMapping("/api/finance/accounts")
@RequiredArgsConstructor
public class FinanceAccountController {

    private final FinanceAccountUseCase accountUseCase;

    @Operation(summary = "계좌 목록 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    public List<FinanceAccountResponse> list(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) UUID groupId) {
        return accountUseCase.list(userId, groupId).stream()
                .map(FinanceAccountResponse::from)
                .toList();
    }

    @Operation(summary = "계좌 등록")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "등록 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "409", description = "이름 중복")
    })
    @PostMapping
    public ResponseEntity<FinanceAccountResponse> create(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) UUID groupId,
            @Valid @RequestBody FinanceAccountRequest request) {
        var saved = accountUseCase.create(userId, groupId, request.toCommand());
        return ResponseEntity.created(URI.create("/api/finance/accounts/" + saved.id()))
                .body(FinanceAccountResponse.from(saved));
    }

    @Operation(summary = "계좌 수정")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "이름 중복")
    })
    @PutMapping("/{id}")
    public FinanceAccountResponse update(
            @Parameter(description = "계좌 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody FinanceAccountRequest request) {
        return FinanceAccountResponse.from(accountUseCase.update(id, userId, request.toCommand()));
    }

    @Operation(summary = "계좌 삭제")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공"),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음")
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @Parameter(description = "계좌 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        accountUseCase.delete(id, userId);
    }
}
