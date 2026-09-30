package com.kista.notify.adapter.out.gateway;

import com.kista.notify.application.port.output.PushNotificationPort;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.domain.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

// FCM 푸시 라우팅의 단일 지점 — "사용자 조회 → FCM 채널 포함 여부 → FcmAdapter 발송" 판정을 여기에만 둔다.
// 스트림 컨슈머(trading-core 체결 푸시)와 알림 리스너(finance 리마인더)가 같은 포트로 호출한다.
@Slf4j
@Component
@RequiredArgsConstructor
class UserPushNotificationAdapter implements PushNotificationPort {

    private final UserPort userPort;     // 수신자 알림 채널 조회
    private final FcmAdapter fcmAdapter; // FCM 발송 — 디바이스 토큰 조회·만료 토큰 정리 포함

    @Override
    public void pushIfEnabled(UUID userId, String title, String body) {
        // 1단계: 수신자 조회 — 탈퇴 직후 도착한 요청 등 없으면 정상 skip
        Optional<User> user = userPort.findById(userId);
        if (user.isEmpty()) {
            log.debug("[userId={}] 푸시 수신자를 찾을 수 없어 건너뜀", userId);
            return;
        }
        // 2단계: 채널이 FCM을 포함할 때만 발송
        if (user.get().notificationChannel().includesFcm()) {
            fcmAdapter.send(userId, title, body);
        }
    }
}
