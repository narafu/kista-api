package com.kista.admin.domain.model;

// privacy.domain.model.PrivacyTradeConflictException own-type — PrivacyQueryHttpAdapter가 내부
// API 409 응답(같은 날짜/종목에 내용이 다른 기준 매매표 존재)을 원래 예외 성격으로 되돌리기 위해
// admin 쪽에서 생성·포착하는 표지 예외
public class AdminPrivacyTradeConflictException extends RuntimeException {
    public AdminPrivacyTradeConflictException(String message) {
        super(message);
    }
}
