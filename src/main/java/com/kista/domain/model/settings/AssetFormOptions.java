package com.kista.domain.model.settings;

import java.util.List;
import java.util.Objects;

// '자산' 등록 폼의 운용전략(strategy) 필드는 여전히 자유 입력 텍스트 컬럼(백킹 테이블 없음)이라
// 관리자 편집 가능한 추천 목록이 필요하다. 구 subcategorySuggestions/institutionSuggestions는
// finance_categories/finance_accounts 실 테이블로, assetClassSuggestions는 AssetClass/Market enum으로
// 완전히 대체됐다 — 유지하면 동일 개념의 SSOT가 두 개가 되므로 삭제.
public record AssetFormOptions(
        List<String> strategySuggestions // 운용전략 추천 목록
) {
    public AssetFormOptions {
        strategySuggestions = List.copyOf(Objects.requireNonNull(strategySuggestions, "strategySuggestions"));
    }

    public static AssetFormOptions defaults() {
        // Strategy.Type이 아니라 의도적인 자유 텍스트 목록이다 — DCA는 실제 자동매매 전략 타입에 없지만
        // 사용자가 개인 메모로 흔히 쓰는 값이라 추천 목록에 포함한다. Strategy.Type과 동기화하려 하지 말 것.
        return new AssetFormOptions(List.of("VR", "INFINITE", "PRIVACY", "DCA"));
    }
}
