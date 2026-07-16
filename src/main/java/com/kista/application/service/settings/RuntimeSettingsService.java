package com.kista.application.service.settings;

import com.kista.domain.model.account.Account.Broker;
import com.kista.domain.model.settings.RuntimeSettings;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.AdminSettingsUseCase;
import com.kista.domain.port.in.RuntimeSettingsUseCase;
import com.kista.domain.port.in.UserUseCase;
import com.kista.domain.port.out.AuditLogPort;
import com.kista.domain.port.out.RuntimeSettingsPort;
import com.kista.domain.port.out.UserPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
class RuntimeSettingsService implements RuntimeSettingsUseCase, AdminSettingsUseCase {

    private final RuntimeSettingsPort settingsPort; // 런타임 설정 영속화 포트
    private final UserPort userPort; // 승인 대기 사용자 조회 포트
    private final UserUseCase userUseCase; // 기존 승인 이벤트를 보존하는 사용자 유스케이스
    private final AuditLogPort auditLogPort; // 관리자 설정 변경 감사 로그 포트

    @Override
    @Transactional(readOnly = true)
    public RuntimeSettings getSettings() {
        return settingsPort.load();
    }

    @Override
    public RuntimeSettings updateSettings(UUID adminId, RuntimeSettings settings) {
        RuntimeSettings previous = settingsPort.loadForUpdate();

        // 검증 완료된 전체 설정을 단일 저장 호출로 반영한다.
        RuntimeSettings saved = settingsPort.save(settings);

        // 승인 설정을 끄는 순간의 PENDING 사용자만 기존 승인 흐름으로 활성화한다.
        if (previous.approvalRequired() && !saved.approvalRequired()) {
            userPort.findAllByStatus(User.UserStatus.PENDING).stream()
                    .map(User::id)
                    .forEach(userUseCase::approve);
        }

        auditLogPort.log(adminId, "RUNTIME_SETTINGS_UPDATE", "RUNTIME_SETTINGS", null, diff(previous, saved));
        return saved;
    }

    // approvalRequired 외에 브로커·전략 활성화 상태 변경도 감사 로그에서 추적 가능하도록 diff만 담는다.
    private Map<String, Object> diff(RuntimeSettings previous, RuntimeSettings saved) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalRequired", saved.approvalRequired());
        Map<String, Boolean> brokerChanges = new LinkedHashMap<>();
        for (Broker broker : Broker.values()) {
            boolean before = previous.brokers().get(broker).enabled();
            boolean after = saved.brokers().get(broker).enabled();
            if (before != after) brokerChanges.put(broker.name(), after);
        }
        if (!brokerChanges.isEmpty()) payload.put("brokers", brokerChanges);
        Map<String, Boolean> strategyChanges = new LinkedHashMap<>();
        for (Strategy.Type type : Strategy.Type.values()) {
            boolean before = previous.strategies().get(type).enabled();
            boolean after = saved.strategies().get(type).enabled();
            if (before != after) strategyChanges.put(type.name(), after);
        }
        if (!strategyChanges.isEmpty()) payload.put("strategies", strategyChanges);
        return payload;
    }
}
