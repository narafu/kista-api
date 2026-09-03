package com.kista.finance.adapter.in.web;

import com.kista.finance.adapter.in.web.dto.FinanceBudgetRequest;
import com.kista.finance.adapter.in.web.dto.FinanceBudgetResponse;
import com.kista.finance.domain.port.in.FinanceBudgetUseCase;
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
@RequestMapping("/api/finance/budgets")
@RequiredArgsConstructor
public class FinanceBudgetController {

    private final FinanceBudgetUseCase budgetUseCase;

    @Operation(summary = "예산 목록 조회", description = "date를 지정하면 그 날짜에 유효한 예산만 반환합니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    public List<FinanceBudgetResponse> list(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) UUID groupId,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return budgetUseCase.list(userId, groupId, categoryId, date).stream()
                .map(FinanceBudgetResponse::from)
                .toList();
    }

    @Operation(summary = "예산 등록")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "등록 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "409", description = "같은 카테고리에 적용 기간이 겹치는 예산 존재")
    })
    @PostMapping
    public ResponseEntity<FinanceBudgetResponse> create(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) UUID groupId,
            @Valid @RequestBody FinanceBudgetRequest request) {
        var saved = budgetUseCase.create(userId, groupId, request.toCommand());
        return ResponseEntity.created(URI.create("/api/finance/budgets/" + saved.id()))
                .body(FinanceBudgetResponse.from(saved));
    }

    @Operation(summary = "예산 수정")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음"),
            @ApiResponse(responseCode = "404", description = "예산을 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "같은 카테고리에 적용 기간이 겹치는 예산 존재")
    })
    @PutMapping("/{id}")
    public FinanceBudgetResponse update(
            @Parameter(description = "예산 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody FinanceBudgetRequest request) {
        return FinanceBudgetResponse.from(budgetUseCase.update(id, userId, request.toCommand()));
    }

    @Operation(summary = "예산 그룹 공유 전환", description = "개인 소유 예산을 소유자의 현재 그룹으로 공유 전환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "전환 성공"),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음"),
            @ApiResponse(responseCode = "404", description = "예산을 찾을 수 없음")
    })
    @PatchMapping("/{id}/share")
    public FinanceBudgetResponse share(
            @Parameter(description = "예산 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        return FinanceBudgetResponse.from(budgetUseCase.shareToGroup(id, userId));
    }

    @Operation(summary = "예산 그룹 공유 해제", description = "그룹 공유 예산을 개인 소유로 되돌립니다. 같은 그룹 멤버면 누구든 가능합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "해제 성공"),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음"),
            @ApiResponse(responseCode = "404", description = "예산을 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "같은 카테고리에 적용 기간이 겹치는 개인 예산 존재")
    })
    @PatchMapping("/{id}/unshare")
    public FinanceBudgetResponse unshare(
            @Parameter(description = "예산 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        return FinanceBudgetResponse.from(budgetUseCase.unshare(id, userId));
    }

    @Operation(summary = "예산 삭제", description = "파생 설정이라 하드 삭제됩니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공"),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음"),
            @ApiResponse(responseCode = "404", description = "예산을 찾을 수 없음")
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @Parameter(description = "예산 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        budgetUseCase.delete(id, userId);
    }
}
