package com.kista.account.domain.model;

import com.kista.sharedkernel.Broker;

public record RegisterAccountCommand(
        String nickname,
        String accountNo,       // 평문 (서비스에서 암호화)
        String appKey,       // 평문
        String secretKey,    // 평문
        Broker broker   // null이면 서비스에서 KIS 기본값 적용
) {}
