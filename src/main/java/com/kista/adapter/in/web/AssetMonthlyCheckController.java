package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.AssetMonthlyCheckRequest;
import com.kista.adapter.in.web.dto.AssetMonthlyCheckResponse;
import com.kista.domain.port.in.AssetMonthlyCheckUseCase;
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

@Tag(name = "자산", description = "개인 자산·부채 기록 등록·조회·수정·삭제 — 자동매매와 무관한 수동 장부")
@RestController
@RequestMapping("/api/asset-monthly-checks")
@RequiredArgsConstructor
public class AssetMonthlyCheckController {

    private final AssetMonthlyCheckUseCase assetMonthlyCheckUseCase;

    @Operation(summary = "월별 기록 완료 상태 목록 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    public List<AssetMonthlyCheckResponse> list(@AuthenticationPrincipal UUID userId) {
        return assetMonthlyCheckUseCase.listByUser(userId).stream()
                .map(AssetMonthlyCheckResponse::from)
                .toList();
    }

    @Operation(summary = "월별 기록 완료 상태 설정", description = "해당 연월의 완료 상태를 등록하거나 갱신합니다(upsert).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장 성공"),
            @ApiResponse(responseCode = "400", description = "연월 형식이 올바르지 않음")
    })
    @PutMapping("/{month}")
    public AssetMonthlyCheckResponse setCompleted(
            @Parameter(description = "연월", example = "2026-08") @PathVariable String month,
            @AuthenticationPrincipal UUID userId,
            @RequestBody AssetMonthlyCheckRequest request) {
        return AssetMonthlyCheckResponse.from(
                assetMonthlyCheckUseCase.setCompleted(userId, month, request.completed())
        );
    }
}
