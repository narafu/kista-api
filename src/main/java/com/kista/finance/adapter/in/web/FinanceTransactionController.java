package com.kista.finance.adapter.in.web;

import com.kista.finance.adapter.in.web.dto.FinanceTransactionRequest;
import com.kista.finance.adapter.in.web.dto.FinanceTransactionResponse;
import com.kista.finance.application.usecase.FinanceTransactionUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "재무")
@RestController
@RequestMapping("/api/finance/transactions")
@RequiredArgsConstructor
public class FinanceTransactionController {

    private final FinanceTransactionUseCase transactionUseCase;

    @Operation(summary = "거래내역 목록 조회", description = "userId=본인UUID로 필터링하면 개인 탭(내가 입력한 내역만)입니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    public List<FinanceTransactionResponse> list(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) UUID groupId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(name = "userId", required = false) UUID filterUserId) {
        return transactionUseCase.list(userId, groupId, from, to, categoryId, filterUserId).stream()
                .map(FinanceTransactionResponse::from)
                .toList();
    }

    @Operation(summary = "거래내역 등록")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "등록 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청")
    })
    @PostMapping
    public ResponseEntity<FinanceTransactionResponse> create(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) UUID groupId,
            @Valid @RequestBody FinanceTransactionRequest request) {
        var saved = transactionUseCase.create(userId, groupId, request.toCommand());
        return ResponseEntity.created(URI.create("/api/finance/transactions/" + saved.id()))
                .body(FinanceTransactionResponse.from(saved));
    }

    @Operation(summary = "거래내역 수정")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음"),
            @ApiResponse(responseCode = "404", description = "거래내역을 찾을 수 없음")
    })
    @PutMapping("/{id}")
    public FinanceTransactionResponse update(
            @Parameter(description = "거래내역 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody FinanceTransactionRequest request) {
        return FinanceTransactionResponse.from(transactionUseCase.update(id, userId, request.toCommand()));
    }

    @Operation(summary = "거래내역 그룹 공유 전환", description = "개인 소유 거래내역을 소유자의 현재 그룹으로 공유 전환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "전환 성공"),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음"),
            @ApiResponse(responseCode = "404", description = "거래내역을 찾을 수 없음")
    })
    @PatchMapping("/{id}/share")
    public FinanceTransactionResponse share(
            @Parameter(description = "거래내역 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        return FinanceTransactionResponse.from(transactionUseCase.shareToGroup(id, userId));
    }

    @Operation(summary = "거래내역 그룹 공유 해제", description = "그룹 공유 거래내역을 개인 소유로 되돌립니다. 같은 그룹 멤버면 누구든 가능합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "해제 성공"),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음"),
            @ApiResponse(responseCode = "404", description = "거래내역을 찾을 수 없음")
    })
    @PatchMapping("/{id}/unshare")
    public FinanceTransactionResponse unshare(
            @Parameter(description = "거래내역 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        return FinanceTransactionResponse.from(transactionUseCase.unshare(id, userId));
    }

    @Operation(summary = "거래내역 삭제")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공"),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음"),
            @ApiResponse(responseCode = "404", description = "거래내역을 찾을 수 없음")
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @Parameter(description = "거래내역 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        transactionUseCase.delete(id, userId);
    }
}
