package com.kista.domain.model.account;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AccountNumberMasker.mask 단위 테스트")
class AccountNumberMaskerTest {

    // null 입력 — 전체 마스킹
    @Test
    @DisplayName("null 입력 시 \"****\" 반환")
    void mask_null_returnsFullyMasked() {
        assertThat(AccountNumberMasker.mask(null)).isEqualTo("****");
    }

    // 숫자만 4자리 이하 — 전체 마스킹 (노출할 자리가 없음)
    @Test
    @DisplayName("숫자 4자리 이하 입력 시 \"****\" 반환")
    void mask_shortNumericInput_returnsFullyMasked() {
        assertThat(AccountNumberMasker.mask("1234")).isEqualTo("****");
        assertThat(AccountNumberMasker.mask("12")).isEqualTo("****");
    }

    // KIS 포맷: "74420614-01" — 하이픈 제거 후 숫자 전체 "7442061401" 마지막 4자리 "1401"
    @Test
    @DisplayName("KIS 하이픈 포맷: 숫자만 추출한 마지막 4자리 노출")
    void mask_kisStyleHyphenated_masksExceptLastFourDigits() {
        String result = AccountNumberMasker.mask("74420614-01");
        assertThat(result).isEqualTo("****1401");
        // 숫자 부분이 4자리를 초과하는 어떤 구간도 그대로 노출되지 않아야 함 (앞자리 미노출 검증)
        assertThat(result).doesNotContain("7442061");
    }

    // TOSS 포맷: "131-01-001931" — 하이픈 2개, 숫자만 "13101001931" 마지막 4자리 "1931"
    @Test
    @DisplayName("TOSS 다중 하이픈 포맷: 숫자만 추출한 마지막 4자리만 노출, 부분 노출 없음")
    void mask_tossStyleMultiHyphen_masksExceptLastFourDigits_noPartialLeak() {
        String result = AccountNumberMasker.mask("131-01-001931");
        assertThat(result).isEqualTo("****1931");
        // 구버전 버그 재현 방지: "****-01-001931" 처럼 하이픈 이후 전체가 노출되면 안 됨
        assertThat(result).doesNotContain("-");
        assertThat(result.length()).isEqualTo(8);
    }
}
