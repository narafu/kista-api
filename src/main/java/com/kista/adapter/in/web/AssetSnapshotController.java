package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.AssetSnapshotRequest;
import com.kista.adapter.in.web.dto.AssetSnapshotResponse;
import com.kista.domain.model.finance.AssetSnapshot;
import com.kista.domain.model.finance.FinanceCategory;
import com.kista.domain.port.in.AssetSnapshotUseCase;
import com.kista.domain.port.out.FinanceAccountPort;
import com.kista.domain.port.out.FinanceCategoryPort;
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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Tag(name = "재무")
@RestController
@RequestMapping("/api/finance/asset-snapshots")
@RequiredArgsConstructor
public class AssetSnapshotController {

    private final AssetSnapshotUseCase assetSnapshotUseCase;
    // 응답 보강(카테고리명·계좌명·최상위 카테고리 ID) 전용 읽기 조회 — UseCase 외 out-port 직접 의존은
    // 이 컨트롤러에 한해 의도적 예외다 (plan §5.5 참고)
    private final FinanceCategoryPort financeCategoryPort;
    private final FinanceAccountPort financeAccountPort;

    @Operation(summary = "자산 기록 목록 조회", description = "기준 날짜 최신순으로 반환합니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    public List<AssetSnapshotResponse> list(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) UUID groupId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID createdBy) {
        return assetSnapshotUseCase.list(userId, groupId, from, to, createdBy).stream()
                .sorted(Comparator.comparing(AssetSnapshot::entryDate).reversed())
                .map(this::enrich)
                .toList();
    }

    @Operation(summary = "자산 기록 등록")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "등록 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청")
    })
    @PostMapping
    public ResponseEntity<AssetSnapshotResponse> create(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) UUID groupId,
            @Valid @RequestBody AssetSnapshotRequest request) {
        AssetSnapshot saved = assetSnapshotUseCase.create(userId, groupId, request.toCommand());
        return ResponseEntity.created(URI.create("/api/finance/asset-snapshots/" + saved.id()))
                .body(enrich(saved));
    }

    @Operation(summary = "자산 기록 수정")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음"),
            @ApiResponse(responseCode = "404", description = "자산 기록을 찾을 수 없음")
    })
    @PutMapping("/{id}")
    public AssetSnapshotResponse update(
            @Parameter(description = "자산 기록 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody AssetSnapshotRequest request) {
        return enrich(assetSnapshotUseCase.update(id, userId, request.toCommand()));
    }

    @Operation(summary = "자산 기록 삭제")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공"),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음"),
            @ApiResponse(responseCode = "404", description = "자산 기록을 찾을 수 없음")
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @Parameter(description = "자산 기록 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        assetSnapshotUseCase.delete(id, userId);
    }

    // 카테고리·계좌 이름을 매 행마다 조회한다(N+1) — 개인 재무 규모(수십~수백 건)에서는 무시할 수 있는 비용이라
    // 지금은 그대로 두고, 필요해지면 나중에 배치 조회로 최적화한다.
    private AssetSnapshotResponse enrich(AssetSnapshot snapshot) {
        FinanceCategory category = financeCategoryPort.findByIdOrThrow(snapshot.categoryId());
        UUID rootCategoryId = category.parentId() != null ? category.parentId() : category.id();
        String accountName = snapshot.accountId() != null
                ? financeAccountPort.findByIdOrThrow(snapshot.accountId()).name()
                : null;
        return new AssetSnapshotResponse(
                snapshot.id(), snapshot.categoryId(), rootCategoryId, category.name(),
                snapshot.accountId(), accountName, snapshot.entryDate(),
                snapshot.assetClass().name(), snapshot.market().name(), snapshot.strategy(), snapshot.amount());
    }
}
