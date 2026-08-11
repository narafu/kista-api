package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.AssetRequest;
import com.kista.adapter.in.web.dto.AssetResponse;
import com.kista.domain.port.in.AssetUseCase;
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

@Tag(name = "자산", description = "개인 자산·부채 기록 등록·조회·수정·삭제 — 자동매매와 무관한 수동 장부")
@RestController
@RequestMapping("/api/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetUseCase assetUseCase;

    @Operation(summary = "내 자산 기록 목록 조회", description = "로그인한 사용자의 전체 자산 기록을 기준 날짜 최신순으로 반환합니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    public List<AssetResponse> list(@AuthenticationPrincipal UUID userId) {
        return assetUseCase.listByUser(userId).stream()
                .map(AssetResponse::from)
                .toList();
    }

    @Operation(summary = "자산 기록 등록")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "등록 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AssetResponse register(@AuthenticationPrincipal UUID userId,
                                   @Valid @RequestBody AssetRequest request) {
        return AssetResponse.from(assetUseCase.register(userId, request.toRegisterCommand()));
    }

    @Operation(summary = "자산 기록 수정")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "403", description = "내 자산 기록이 아님"),
            @ApiResponse(responseCode = "404", description = "자산 기록을 찾을 수 없음")
    })
    @PutMapping("/{id}")
    public AssetResponse update(
            @Parameter(description = "자산 기록 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody AssetRequest request) {
        return AssetResponse.from(
                assetUseCase.update(id, userId, request.toUpdateCommand())
        );
    }

    @Operation(summary = "자산 기록 삭제")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공"),
            @ApiResponse(responseCode = "403", description = "내 자산 기록이 아님"),
            @ApiResponse(responseCode = "404", description = "자산 기록을 찾을 수 없음")
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @Parameter(description = "자산 기록 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        assetUseCase.delete(id, userId);
    }
}
