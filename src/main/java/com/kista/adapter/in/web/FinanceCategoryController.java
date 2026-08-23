package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.FinanceCategoryRequest;
import com.kista.adapter.in.web.dto.FinanceCategoryResponse;
import com.kista.domain.model.finance.FinanceCategory;
import com.kista.domain.port.in.FinanceCategoryUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Tag(name = "재무", description = "가계부(카테고리·계좌·예산·거래·자산·그룹) API")
@RestController
@RequestMapping("/api/finance/categories")
@RequiredArgsConstructor
public class FinanceCategoryController {

    private final FinanceCategoryUseCase categoryUseCase;

    @Operation(summary = "카테고리 목록 조회", description = "카테고리를 중첩된 트리로 반환합니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    public List<FinanceCategoryResponse> list(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) UUID groupId,
            @RequestParam(required = false) FinanceCategory.Type type) {
        List<FinanceCategory> flat = categoryUseCase.list(userId, groupId, type);
        Map<UUID, List<FinanceCategory>> byParent = flat.stream()
                .filter(c -> c.parentId() != null)
                .collect(Collectors.groupingBy(FinanceCategory::parentId));
        return flat.stream()
                .filter(c -> c.parentId() == null)
                .map(root -> toResponse(root, byParent))
                .toList();
    }

    // 재귀적으로 카테고리 계층을 조립해 임의 depth 트리 구성
    private FinanceCategoryResponse toResponse(FinanceCategory category, Map<UUID, List<FinanceCategory>> byParent) {
        List<FinanceCategoryResponse> children = byParent.getOrDefault(category.id(), List.of()).stream()
                .map(child -> toResponse(child, byParent))
                .toList();
        return FinanceCategoryResponse.from(category, children);
    }

    @Operation(summary = "카테고리 등록")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "등록 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "403", description = "그룹 접근 권한 없음"),
            @ApiResponse(responseCode = "409", description = "같은 부모 아래 이름 중복")
    })
    @PostMapping
    public ResponseEntity<FinanceCategoryResponse> create(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) UUID groupId,
            @Valid @RequestBody FinanceCategoryRequest request) {
        if (request.type() == null) {
            // DB NOT NULL 위반이 FinanceCategoryPersistenceAdapter에서 무조건 DuplicateNameException(409)으로
            // 오분류되는 걸 막기 위해 여기서 먼저 400을 낸다 — @NotNull은 update()가 공유하는 DTO라 걸 수 없다.
            throw new IllegalArgumentException("type은 필수입니다");
        }
        FinanceCategory saved = categoryUseCase.create(userId, groupId, request.toCommand());
        return ResponseEntity.created(URI.create("/api/finance/categories/" + saved.id()))
                .body(FinanceCategoryResponse.from(saved, List.of()));
    }

    @Operation(summary = "카테고리 수정", description = "이름·정렬 순서만 반영됩니다 — 타입·상위 카테고리는 생성 후 불변입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "403", description = "시스템 카테고리이거나 접근 권한 없음"),
            @ApiResponse(responseCode = "404", description = "카테고리를 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "같은 부모 아래 이름 중복")
    })
    @PutMapping("/{id}")
    public FinanceCategoryResponse update(
            @Parameter(description = "카테고리 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody FinanceCategoryRequest request) {
        return FinanceCategoryResponse.from(categoryUseCase.update(id, userId, request.toCommand()), List.of());
    }

    @Operation(summary = "카테고리 그룹 공유 전환", description = "개인 소유 카테고리를 소유자의 현재 그룹으로 공유 전환합니다. 하위 카테고리도 함께 전환됩니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "전환 성공"),
            @ApiResponse(responseCode = "403", description = "시스템 카테고리이거나 접근 권한 없음"),
            @ApiResponse(responseCode = "404", description = "카테고리를 찾을 수 없음")
    })
    @PatchMapping("/{id}/share")
    public FinanceCategoryResponse share(
            @Parameter(description = "카테고리 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        return FinanceCategoryResponse.from(categoryUseCase.shareToGroup(id, userId), List.of());
    }

    @Operation(summary = "카테고리 그룹 공유 해제", description = "그룹 공유 카테고리를 개인 소유로 되돌립니다. 하위 카테고리도 함께 전환됩니다. 같은 그룹 멤버면 누구든 가능합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "해제 성공"),
            @ApiResponse(responseCode = "403", description = "시스템 카테고리이거나 접근 권한 없음"),
            @ApiResponse(responseCode = "404", description = "카테고리를 찾을 수 없음")
    })
    @PatchMapping("/{id}/unshare")
    public FinanceCategoryResponse unshare(
            @Parameter(description = "카테고리 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        return FinanceCategoryResponse.from(categoryUseCase.unshare(id, userId), List.of());
    }

    @Operation(summary = "카테고리 삭제", description = "소프트 삭제 — 하위 카테고리도 함께 삭제됩니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공"),
            @ApiResponse(responseCode = "403", description = "시스템 카테고리이거나 접근 권한 없음"),
            @ApiResponse(responseCode = "404", description = "카테고리를 찾을 수 없음")
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(
            @Parameter(description = "카테고리 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        categoryUseCase.delete(id, userId);
    }
}
