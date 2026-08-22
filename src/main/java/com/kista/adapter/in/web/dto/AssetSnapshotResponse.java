package com.kista.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.UUID;

// categoryName/accountName/rootCategoryId는 UI가 카테고리·계좌 목록을 재조회하지 않고 이름 표시·순자산/총부채
// 판정(rootCategoryId == FinanceCategory.SYSTEM_LOAN_ID)을 할 수 있도록 컨트롤러가 조회해 채운다.
// 도메인 AssetSnapshot record에는 없는 필드라 여기서는 from() 팩토리 없이 컨트롤러가 직접 생성한다.
public record AssetSnapshotResponse(
        @Schema(description = "자산 기록 고유 ID")
        UUID id,
        @Schema(description = "그룹 ID (없으면 개인 소유)")
        UUID groupId,
        @Schema(description = "카테고리 ID")
        UUID categoryId,
        @Schema(description = "최상위(L1) 카테고리 ID — 대출이면 순자산 계산 시 부채로 취급")
        UUID rootCategoryId,
        @Schema(description = "카테고리명")
        String categoryName,
        @Schema(description = "계좌 ID (없으면 null)")
        UUID accountId,
        @Schema(description = "계좌명 (없으면 null)")
        String accountName,
        @Schema(description = "기준 날짜", example = "2026-08-01")
        LocalDate entryDate,
        @Schema(description = "자산군", example = "EQUITY")
        String assetClass,
        @Schema(description = "시장", example = "GLOBAL")
        String market,
        @Schema(description = "운용전략", example = "VR")
        String strategy,
        @Schema(description = "금액 (원화 정수)", example = "1000000")
        long amount
) {}
