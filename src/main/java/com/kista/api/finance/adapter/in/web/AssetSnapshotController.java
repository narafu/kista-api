package com.kista.api.finance.adapter.in.web;

import com.kista.api.finance.adapter.in.web.dto.AssetCheckResponse;
import com.kista.api.finance.adapter.in.web.dto.AssetCompositionResponse;
import com.kista.api.finance.adapter.in.web.dto.AssetSummaryResponse;
import com.kista.api.finance.adapter.in.web.dto.AssetTrendResponse;
import com.kista.api.finance.domain.model.AssetSnapshotAggregates;
import com.kista.api.finance.adapter.in.web.dto.AssetSnapshotRequest;
import com.kista.api.finance.adapter.in.web.dto.AssetSnapshotResponse;
import com.kista.api.finance.domain.model.AssetClass;
import com.kista.api.finance.domain.model.AssetSnapshot;
import com.kista.api.finance.domain.model.FinanceAccount;
import com.kista.api.finance.domain.model.FinanceCategory;
import com.kista.api.finance.domain.model.Market;
import com.kista.shared.platform.page.Items;
import com.kista.api.finance.application.service.AssetSnapshotService;
import com.kista.api.finance.application.port.output.FinanceAccountPort;
import com.kista.api.finance.application.port.output.FinanceCategoryPort;
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
import java.time.YearMonth;
import java.util.UUID;

@Tag(name = "재무")
@RestController
@RequestMapping("/api/finance/asset-snapshots")
@RequiredArgsConstructor
public class AssetSnapshotController {

    private final AssetSnapshotService assetSnapshotService;
    // 응답 보강(카테고리명·계좌명·최상위 카테고리 ID) 전용 읽기 조회 — 서비스 외 out-port 직접 의존은
    // 이 컨트롤러에 한해 의도적 예외다 (plan §5.5 참고)
    private final FinanceCategoryPort financeCategoryPort;
    private final FinanceAccountPort financeAccountPort;

    @Operation(summary = "자산 기록 목록 조회", description = "[전량형: 한 달치 — month 필수] 기준 날짜 최신순(동일하면 id 역순). categoryId는 하위 카테고리를 포함합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "month 누락 또는 형식 오류")
    })
    @GetMapping
    public Items<AssetSnapshotResponse> list(
            @AuthenticationPrincipal UUID userId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) AssetClass assetClass,
            @RequestParam(required = false) Market market,
            @RequestParam(name = "userId", required = false) UUID filterUserId) {
        return Items.of(assetSnapshotService.list(userId, month, categoryId, assetClass, market, filterUserId).stream()
                .map(this::enrich)
                .toList());
    }

    @Operation(summary = "자산 월 요약", description = "month 생략 시 가장 최근 기록 월. 직전 기록 월 요약(previous)을 함께 반환합니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/summary")
    public AssetSummaryResponse summary(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return AssetSummaryResponse.from(assetSnapshotService.summary(userId, month));
    }

    @Operation(summary = "자산 추이", description = "기록 월 중 endMonth 이하의 최근 months개를 월 오름차순으로 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "months가 1 미만")
    })
    @GetMapping("/trend")
    public AssetTrendResponse trend(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) Integer months,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth endMonth) {
        return AssetTrendResponse.from(assetSnapshotService.trend(userId, months, endMonth));
    }

    @Operation(summary = "자산 구성비", description = "basis=CATEGORY(대출 포함 L1) 또는 ASSET_CLASS(대출 제외 자산군)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 basis 또는 months")
    })
    @GetMapping("/composition")
    public AssetCompositionResponse composition(
            @AuthenticationPrincipal UUID userId,
            @RequestParam AssetSnapshotAggregates.Basis basis,
            @RequestParam(required = false) Integer months,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth endMonth) {
        return AssetCompositionResponse.from(assetSnapshotService.composition(userId, basis, months, endMonth));
    }

    @Operation(summary = "월별 기록 점검", description = "누락 L1 카테고리·전월 대비 누락 계좌·기준일별 건수를 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "month 누락 또는 형식 오류")
    })
    @GetMapping("/check")
    public AssetCheckResponse check(
            @AuthenticationPrincipal UUID userId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return AssetCheckResponse.from(assetSnapshotService.check(userId, month));
    }

    @Operation(summary = "자산 기록 등록")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "등록 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청")
    })
    @PostMapping
    public ResponseEntity<AssetSnapshotResponse> create(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false, defaultValue = "false") boolean shareToGroup,
            @Valid @RequestBody AssetSnapshotRequest request) {
        AssetSnapshot saved = assetSnapshotService.create(userId, shareToGroup, request.toCommand());
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
        return enrich(assetSnapshotService.update(id, userId, request.toCommand()));
    }

    @Operation(summary = "자산 기록 그룹 공유 전환", description = "개인 소유 자산 기록을 소유자의 현재 그룹으로 공유 전환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "전환 성공"),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음"),
            @ApiResponse(responseCode = "404", description = "자산 기록을 찾을 수 없음")
    })
    @PatchMapping("/{id}/share")
    public AssetSnapshotResponse share(
            @Parameter(description = "자산 기록 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        return enrich(assetSnapshotService.shareToGroup(id, userId));
    }

    @Operation(summary = "자산 기록 그룹 공유 해제", description = "그룹 공유 자산 기록을 개인 소유로 되돌립니다. 같은 그룹 멤버면 누구든 가능합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "해제 성공"),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음"),
            @ApiResponse(responseCode = "404", description = "자산 기록을 찾을 수 없음")
    })
    @PatchMapping("/{id}/unshare")
    public AssetSnapshotResponse unshare(
            @Parameter(description = "자산 기록 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        return enrich(assetSnapshotService.unshare(id, userId));
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
        assetSnapshotService.delete(id, userId);
    }

    // 카테고리·계좌 이름을 매 행마다 조회한다(N+1) — 개인 재무 규모(수십~수백 건)에서는 무시할 수 있는 비용이라
    // 지금은 그대로 두고, 필요해지면 나중에 배치 조회로 최적화한다.
    private AssetSnapshotResponse enrich(AssetSnapshot snapshot) {
        FinanceCategory category = financeCategoryPort.findByIdOrThrow(snapshot.categoryId());
        // 임의 depth 트리 루트까지 거슬러 올라감 — parentId가 null일 때까지 반복 조회
        FinanceCategory root = category;
        while (root.parentId() != null) {
            root = financeCategoryPort.findByIdOrThrow(root.parentId());
        }
        UUID rootCategoryId = root.id();
        // 그룹 공유 스냅샷이 다른 멤버 계좌를 가리켜도 이름·기관·소유자가 채워지도록 서버에서 조인한다
        FinanceAccount account = snapshot.accountId() != null
                ? financeAccountPort.findByIdOrThrow(snapshot.accountId())
                : null;
        String accountName = account != null ? account.name() : null;
        String accountInstitution = account != null ? account.institution() : null;
        String accountOwner = account != null ? account.owner() : null;
        return new AssetSnapshotResponse(
                snapshot.id(), snapshot.groupId(), snapshot.categoryId(), rootCategoryId, category.name(),
                snapshot.accountId(), accountName, accountInstitution, accountOwner, snapshot.entryDate(),
                snapshot.assetClass().name(), snapshot.market().name(), snapshot.strategy(), snapshot.memo(), snapshot.amount());
    }
}
