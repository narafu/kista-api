package com.kista.finance.adapter.in.web;

import com.kista.finance.adapter.in.web.dto.MonthlyClosingRequest;
import com.kista.finance.adapter.in.web.dto.MonthlyClosingResponse;
import com.kista.finance.application.usecase.MonthlyClosingUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "재무")
@RestController
@RequestMapping("/api/finance/monthly-closings")
@RequiredArgsConstructor
public class MonthlyClosingController {

    private final MonthlyClosingUseCase monthlyClosingUseCase;

    @Operation(summary = "월별 마감 상태 목록 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    public List<MonthlyClosingResponse> list(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) UUID groupId) {
        return monthlyClosingUseCase.list(userId, groupId).stream()
                .map(MonthlyClosingResponse::from)
                .toList();
    }

    @Operation(summary = "월별 마감 상태 전환", description = "해당 연월의 완료 상태를 등록하거나 갱신합니다(upsert). 리소스 전체 교체가 아닌 상태 전이라 PATCH를 사용합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장 성공"),
            @ApiResponse(responseCode = "400", description = "연월 형식이 올바르지 않음")
    })
    @PatchMapping("/{month}")
    public MonthlyClosingResponse setCompleted(
            @Parameter(description = "연월", example = "2026-08") @PathVariable String month,
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) UUID groupId,
            @RequestBody MonthlyClosingRequest request) {
        return MonthlyClosingResponse.from(
                monthlyClosingUseCase.setCompleted(userId, groupId, month, request.completed()));
    }
}
