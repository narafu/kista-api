package com.kista.finance.application.event;

import java.util.UUID;

// 이번 달 가계부 등록이 없는 사용자에게 리마인더를 보내야 한다는 알림 요청 — finance가 발행하고 notify가 구독해
// 알림 설정 게이트·채널 라우팅(텔레그램 봇·FCM)을 처리한다(같은 프로세스 root 안). body는 아이콘 포함 완성 문구.
public record FinanceRegistrationReminderDueEvent(UUID userId, String title, String body) {}
