package com.kista.domain.model.settings;

import com.kista.domain.model.asset.AssetCategory;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// '자산' 메뉴 등록 폼의 세부 카테고리/기관/자산군/운용전략 추천 목록 — 필드 자체는 자유 입력을 유지하므로
// StrategyFieldSettings/BenchmarkFieldSettings와 달리 defaultValue·허용값 강제가 없다.
public record AssetFormOptions(
        Map<AssetCategory, List<String>> subcategorySuggestions, // 카테고리별 세부 카테고리 추천 목록
        List<String> institutionSuggestions, // 기관 추천 목록
        List<String> assetClassSuggestions, // 자산군 추천 목록
        List<String> strategySuggestions // 운용전략 추천 목록
) {
    public AssetFormOptions {
        subcategorySuggestions = immutableEnumMap(subcategorySuggestions);
        institutionSuggestions = List.copyOf(Objects.requireNonNull(institutionSuggestions, "institutionSuggestions"));
        assetClassSuggestions = List.copyOf(Objects.requireNonNull(assetClassSuggestions, "assetClassSuggestions"));
        strategySuggestions = List.copyOf(Objects.requireNonNull(strategySuggestions, "strategySuggestions"));
    }

    public static AssetFormOptions defaults() {
        Map<AssetCategory, List<String>> subcategories = new EnumMap<>(AssetCategory.class);
        subcategories.put(AssetCategory.INVESTMENT, List.of("연금저축펀드", "IRP", "ISA", "일반계좌", "거래소", "개인지갑"));
        subcategories.put(AssetCategory.SAVINGS, List.of("주택청약종합저축", "연금저축보험"));
        subcategories.put(AssetCategory.LOAN, List.of("전세자금대출", "신용대출", "오토할부"));
        subcategories.put(AssetCategory.REAL_ESTATE, List.of("전세임차보증금"));
        return new AssetFormOptions(
                subcategories,
                List.of("토스증권", "메리츠증권", "삼성증권", "한국투자증권", "NH증권", "토스뱅크", "국민은행", "우리은행",
                        "업비트", "빗썸", "edgeX", "교보생명", "라이프플래닛"),
                List.of("미국주식", "기타주식", "금/은", "크립토", "원화", "달러"),
                // Strategy.Type이 아니라 의도적인 자유 텍스트 목록이다 — DCA는 실제 자동매매 전략 타입에 없지만
                // 사용자가 개인 메모로 흔히 쓰는 값이라 추천 목록에 포함한다. Strategy.Type과 동기화하려 하지 말 것.
                List.of("VR", "INFINITE", "PRIVACY", "DCA"));
    }

    // brokers/strategies 맵(RuntimeSettings.immutableEnumMap)과 달리 누락된 카테고리 키를 거부하지 않고
    // 빈 목록으로 채운다 — 이 필드는 순수 추천 목록이라 "정책 누락"이 아니라 "추천값 없음"일 뿐이고,
    // 완전성을 강제하면 향후 AssetCategory에 새 값이 추가될 때 기존 저장 행 역직렬화가 전부 실패해
    // 설정 조회 전체가 500으로 죽는 위험(RuntimeSettingsPersistenceAdapter의 브로커/전략 백필과 같은 부류의
    // 장애)을 이 필드에도 그대로 옮겨오게 된다.
    private static Map<AssetCategory, List<String>> immutableEnumMap(Map<AssetCategory, List<String>> values) {
        Objects.requireNonNull(values, "subcategorySuggestions");
        EnumMap<AssetCategory, List<String>> copy = new EnumMap<>(AssetCategory.class);
        for (AssetCategory category : AssetCategory.values()) {
            List<String> suggestions = values.get(category);
            copy.put(category, suggestions == null ? List.of() : List.copyOf(suggestions));
        }
        return Map.copyOf(copy);
    }
}
