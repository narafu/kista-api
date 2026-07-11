package com.kista.adapter.out.heartbeat;

import com.kista.domain.port.out.HeartbeatPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
class HeartbeatAdapter implements HeartbeatPort {

    private final RestTemplate heartbeatRestTemplate; // 빈 이름과 필드명 일치 필수
    private final HeartbeatProperties properties;

    @Override
    public void pingOpen() {
        ping(properties.openUrl(), "open");
    }

    @Override
    public void pingClose() {
        ping(properties.closeUrl(), "close");
    }

    // 핑 실패는 매매에 영향 없어야 함 — 로그만 남기고 삼킴
    private void ping(String url, String name) {
        if (url == null || url.isBlank()) return;
        try {
            heartbeatRestTemplate.getForObject(url, String.class);
            log.info("heartbeat {} 핑 완료", name);
        } catch (Exception e) {
            log.warn("heartbeat {} 핑 실패: {}", name, e.getMessage());
        }
    }
}
