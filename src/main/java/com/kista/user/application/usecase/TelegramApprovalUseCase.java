package com.kista.user.application.usecase;

import java.util.UUID;

// 텔레그램 관리자 승인/거절 명령 — notify(webhook transport)가 명령을 소유한 이 유스케이스로 위임한다.
// 승인/거절 실행과 그 의미는 user 도메인 소관, notify는 텔레그램 메시지 포맷팅만 담당한다.
public interface TelegramApprovalUseCase {

    enum Action { APPROVE, REJECT }

    // action이 APPROVE/REJECT면 UserUseCase.approve/reject를 실행한다
    void handle(Action action, UUID targetUserId);
}
