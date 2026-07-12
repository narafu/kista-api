package com.kista.domain.model.account;

// 계좌번호 마스킹 단일 알고리즘 — KIS(하이픈 1개)·TOSS(하이픈 2개) 포맷 모두 대응
// 숫자 이외 문자(하이픈 등)를 모두 제거한 뒤 마지막 4자리만 노출한다.
// 하이픈 위치에 따라 부분 노출이 발생하던 기존 3개 DTO의 개별 마스킹 로직을 대체한다.
public final class AccountNumberMasker {

    private AccountNumberMasker() {
    }

    public static String mask(String accountNo) {
        if (accountNo == null) return "****";
        String digits = accountNo.replaceAll("\\D", "");
        if (digits.length() <= 4) return "****";
        return "****" + digits.substring(digits.length() - 4);
    }
}
