package com.kista.domain.model.account;

public record RegisterAccountCommand(
        String nickname,
        String accountNo,       // 평문 (서비스에서 암호화)
        String kisAppKey,       // 평문
        String kisSecretKey,    // 평문
        String kisAccountType,  // KIS: 계좌 상품 코드 기본값 "01", Toss: null
        Account.Broker broker   // null이면 서비스에서 KIS 기본값 적용
) {}
