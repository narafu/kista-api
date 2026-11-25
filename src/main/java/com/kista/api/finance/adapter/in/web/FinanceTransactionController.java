package com.kista.api.finance.adapter.in.web;

import com.kista.api.finance.adapter.in.web.dto.BudgetProgressResponse;
import com.kista.api.finance.adapter.in.web.dto.FlowSummaryResponse;
import com.kista.api.finance.adapter.in.web.dto.FlowTrendResponse;
import com.kista.api.finance.domain.model.FinanceCategory;
import com.kista.api.finance.domain.model.FlowPeriod;
import com.kista.api.finance.adapter.in.web.dto.FinanceTransactionRequest;
import com.kista.api.finance.adapter.in.web.dto.FinanceTransactionResponse;
import com.kista.api.finance.application.service.FinanceTransactionService;
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
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@Tag(name = "재무")
@RestController
@RequestMapping("/api/finance/transactions")
@RequiredArgsConstructor
public class FinanceTransactionController {

    private final FinanceTransactionService financeTransactionService;

    @Operation(summary = "거래내역 목록 조회", description = "userId=본인UUID로 필터링하면 개인 탭(내가 입력한 내역만)입니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    public List<FinanceTransactionResponse> list(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(name = "userId", required = false) UUID filterUserId) {
        return financeTransactionService.list(userId, from, to, categoryId, filterUserId).stream()
                .map(FinanceTransactionResponse::from)
                .toList();
    }

    @Operation(summary = "거래 기간 요약", description = "수입·소비·저축 한 유형의 합계·건수·전월/전년 비교·월평균. 기준일은 서버 KST 오늘입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 파라미터(type=ASSET 등)")
    })
    @GetMapping("/summary")
    public FlowSummaryResponse summary(
            @AuthenticationPrincipal UUID userId,
            @RequestParam FinanceCategory.Type type,
            @RequestParam FlowPeriod.Mode mode,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return FlowSummaryResponse.from(financeTransactionService.summary(userId, type, new FlowPeriod(month, mode)));
    }

    @Operation(summary = "거래 추이", description = "월간은 월별, 연간은 연도별 합계와 루트 카테고리별 합계. range 생략 시 가장 오래된 거래부터 전체.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 파라미터(type=ASSET, range<1 등)")
    })
    @GetMapping("/trend")
    public FlowTrendResponse trend(
            @AuthenticationPrincipal UUID userId,
            @RequestParam FinanceCategory.Type type,
            @RequestParam FlowPeriod.Mode mode,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam(required = false) Integer range) {
        return FlowTrendResponse.from(financeTransactionService.trend(userId, type, new FlowPeriod(month, mode), range));
    }

    @Operation(summary = "예산 대비 실적", description = "예산 카테고리 트리(소계 포함)와 예산 없는 실적 카테고리.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 파라미터(type=ASSET 등)")
    })
    @GetMapping("/budget-progress")
    public BudgetProgressResponse budgetProgress(
            @AuthenticationPrincipal UUID userId,
            @RequestParam FinanceCategory.Type type,
            @RequestParam FlowPeriod.Mode mode,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return BudgetProgressResponse.from(financeTransactionService.budgetProgress(userId, type, new FlowPeriod(month, mode)));
    }

    @Operation(summary = "거래내역 등록")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "등록 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청")
    })
    @PostMapping
    public ResponseEntity<FinanceTransactionResponse> create(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false, defaultValue = "false") boolean shareToGroup,
            @Valid @RequestBody FinanceTransactionRequest request) {
        var saved = financeTransactionService.create(userId, shareToGroup, request.toCommand());
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
        return FinanceTransactionResponse.from(financeTransactionService.update(id, userId, request.toCommand()));
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
        return FinanceTransactionResponse.from(financeTransactionService.shareToGroup(id, userId));
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
        return FinanceTransactionResponse.from(financeTransactionService.unshare(id, userId));
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
        financeTransactionService.delete(id, userId);
    }
}
