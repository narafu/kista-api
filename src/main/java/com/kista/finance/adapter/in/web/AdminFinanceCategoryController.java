package com.kista.finance.adapter.in.web;

import com.kista.finance.adapter.in.web.dto.FinanceCategoryRequest;
import com.kista.finance.adapter.in.web.dto.FinanceCategoryResponse;
import com.kista.finance.domain.model.FinanceCategory;
import com.kista.finance.domain.port.in.FinanceCategoryUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

// 그룹 공용 시스템 카테고리(groupId=null) 관리 전용 — 그룹 사용자용 FinanceCategoryController와는 별도 컨트롤러로
// 완전히 분리한다. /api/admin/** 전역 매처(SecurityConfig)가 ADMIN 권한을 강제하므로 별도 권한 체크는 불필요.
@Slf4j
@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/finance/categories")
@RequiredArgsConstructor
public class AdminFinanceCategoryController {

    private final FinanceCategoryUseCase categoryUseCase;

    @Operation(summary = "시스템 카테고리 목록 조회", description = "그룹 공용 시스템 카테고리를 중첩된 트리로 반환합니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    public List<FinanceCategoryResponse> list(@RequestParam(required = false) FinanceCategory.Type type) {
        List<FinanceCategory> flat = categoryUseCase.listSystem(type);
        // FinanceCategoryController.list()와 같은 재귀 트리 조립 패턴 — 그룹 사용자용 컨트롤러는 건드리지 않는다.
        Map<UUID, List<FinanceCategory>> byParent = flat.stream()
                .filter(c -> c.parentId() != null)
                .collect(Collectors.groupingBy(FinanceCategory::parentId));
        return flat.stream()
                .filter(c -> c.parentId() == null)
                .map(root -> toResponse(root, byParent))
                .toList();
    }

    private FinanceCategoryResponse toResponse(FinanceCategory category, Map<UUID, List<FinanceCategory>> byParent) {
        List<FinanceCategoryResponse> children = byParent.getOrDefault(category.id(), List.of()).stream()
                .map(child -> toResponse(child, byParent))
                .toList();
        return FinanceCategoryResponse.from(category, children);
    }

    // uq_finance_categories_group_parent_name은 "WHERE group_id IS NOT NULL"이라 시스템 카테고리(groupId=null)에는
    // 걸리지 않는다 — 그룹 카테고리 등록과 달리 이름 중복이 DB 레벨에서 차단되지 않으므로 409 문서를 달지 않는다.
    @Operation(summary = "시스템 카테고리 등록")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "등록 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청")
    })
    @PostMapping
    public ResponseEntity<FinanceCategoryResponse> create(
            @AuthenticationPrincipal UUID adminId,
            @Valid @RequestBody FinanceCategoryRequest request) {
        if (request.type() == null) {
            throw new IllegalArgumentException("type은 필수입니다");
        }
        FinanceCategory saved = categoryUseCase.createSystem(request.toCommand());
        log.info("시스템 카테고리 등록: adminId={}, categoryId={}", adminId, saved.id());
        return ResponseEntity.created(URI.create("/api/admin/finance/categories/" + saved.id()))
                .body(FinanceCategoryResponse.from(saved, List.of()));
    }

    @Operation(summary = "시스템 카테고리 수정", description = "이름·정렬 순서만 반영됩니다 — 타입·상위 카테고리는 생성 후 불변입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "400", description = "시스템 카테고리가 아님"),
            @ApiResponse(responseCode = "404", description = "카테고리를 찾을 수 없음")
    })
    @PutMapping("/{id}")
    public FinanceCategoryResponse update(
            @Parameter(description = "카테고리 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID adminId,
            @Valid @RequestBody FinanceCategoryRequest request) {
        FinanceCategoryResponse response = FinanceCategoryResponse.from(
                categoryUseCase.updateSystem(id, request.toCommand()), List.of());
        log.info("시스템 카테고리 수정: adminId={}, categoryId={}", adminId, id);
        return response;
    }

    @Operation(summary = "시스템 카테고리 삭제", description = "소프트 삭제 — 하위 카테고리도 함께 삭제됩니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공"),
            @ApiResponse(responseCode = "400", description = "시스템 카테고리가 아님"),
            @ApiResponse(responseCode = "404", description = "카테고리를 찾을 수 없음")
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @Parameter(description = "카테고리 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID adminId) {
        categoryUseCase.deleteSystem(id);
        log.info("시스템 카테고리 삭제: adminId={}, categoryId={}", adminId, id);
    }
}
