package com.kista.admin.application.service;

import com.kista.account.domain.model.Account.Broker;
import com.kista.admin.domain.model.RuntimeSettings;
import com.kista.strategyconfig.domain.model.Strategy;
import com.kista.user.domain.model.User;
import com.kista.admin.application.usecase.AdminSettingsUseCase;
import com.kista.admin.application.usecase.RuntimeSettingsUseCase;
import com.kista.user.application.usecase.UserUseCase;
import com.kista.admin.application.port.output.AuditLogPort;
import com.kista.admin.application.port.output.RuntimeSettingsPort;
import com.kista.account.application.port.output.BrokerEnabledPort;
import com.kista.user.application.port.output.ApprovalPolicyPort;
import com.kista.user.application.port.output.UserPort;
import com.kista.strategyconfig.application.port.output.StrategyCreationPolicyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.kista.sharedkernel.UserStatus;
import com.kista.sharedkernel.StrategyType;

@Service
@RequiredArgsConstructor
@Transactional
class RuntimeSettingsService implements RuntimeSettingsUseCase, AdminSettingsUseCase, ApprovalPolicyPort, BrokerEnabledPort,
        StrategyCreationPolicyPort {

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
    @Transactional
    public boolean approvalRequiredForUpdate() {
        return settingsPort.loadForUpdate().approvalRequired();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean enabled(Broker broker) {
        return settingsPort.load().brokers().get(broker).enabled();
    }

    // strategy-config가 소비하는 own-type 포트 구현 — admin 내부 StrategyCreationSettings를
    // trading 소유 타입으로 매핑해 반환한다(strategy-config↔admin 순환 해소).
    @Override
    @Transactional(readOnly = true)
    public Optional<com.kista.trading.domain.strategy.StrategyCreationSettings> find(StrategyType type) {
        com.kista.admin.domain.model.StrategyCreationSettings settings = settingsPort.load().strategies().get(type);
        return Optional.ofNullable(settings).map(RuntimeSettingsService::toTradingSettings);
    }

    // admin StrategyCreationSettings → trading 자체 타입. 필드 구조 동일, RecurringMode만 valueOf(name()).
    private static com.kista.trading.domain.strategy.StrategyCreationSettings toTradingSettings(
            com.kista.admin.domain.model.StrategyCreationSettings s) {
        return new com.kista.trading.domain.strategy.StrategyCreationSettings(
                s.enabled(),
                mapField(s.ticker()),
                mapField(s.divisionCount()),
                mapRecurringField(s.recurringMode()),
                mapField(s.bandWidth()),
                mapField(s.intervalWeeks()));
    }

    // 동일 원소 타입 필드는 그대로 재래핑 — trading StrategyFieldSettings 생성자가 List.copyOf로 불변 복제한다.
    private static <T> com.kista.trading.domain.strategy.StrategyFieldSettings<T> mapField(
            com.kista.admin.domain.model.StrategyFieldSettings<T> f) {
        if (f == null) return null;
        return new com.kista.trading.domain.strategy.StrategyFieldSettings<>(
                f.customizable(), f.allowedValues(), f.defaultValue());
    }

    // RecurringMode는 상수명 byte-identical이라 valueOf(name())으로 trading enum에 매핑한다.
    private static com.kista.trading.domain.strategy.StrategyFieldSettings<com.kista.trading.domain.strategy.RecurringMode> mapRecurringField(
            com.kista.admin.domain.model.StrategyFieldSettings<com.kista.admin.domain.model.RecurringMode> f) {
        if (f == null) return null;
        return new com.kista.trading.domain.strategy.StrategyFieldSettings<>(
                f.customizable(),
                f.allowedValues().stream().map(m -> com.kista.trading.domain.strategy.RecurringMode.valueOf(m.name())).toList(),
                com.kista.trading.domain.strategy.RecurringMode.valueOf(f.defaultValue().name()));
    }

    @Override
    public RuntimeSettings updateSettings(UUID adminId, RuntimeSettings settings, boolean benchmarksProvided) {
        RuntimeSettings previous = settingsPort.loadForUpdate();

        // benchmarks는 생략 시 기존 값 유지가 문서화된 예외 규칙 — auth/brokers/strategies는 여전히 전체 교체.
        // settings.benchmarks()는 toDomain() 변환 과정에서 이미 기본값으로 치환되어 null 여부로 생략을 판별할 수 없으므로
        // 컨트롤러가 전달한 benchmarksProvided(요청 DTO 단계의 null 여부)를 기준으로 판단한다.
        RuntimeSettings effective = benchmarksProvided
                ? settings
                : new RuntimeSettings(settings.approvalRequired(), settings.brokers(), settings.strategies(),
                        previous.benchmarks());

        // 검증 완료된 전체 설정을 단일 저장 호출로 반영한다.
        RuntimeSettings saved = settingsPort.save(effective);

        // 승인 설정을 끄는 순간의 PENDING 사용자만 기존 승인 흐름으로 활성화한다.
        if (previous.approvalRequired() && !saved.approvalRequired()) {
            userPort.findAllByStatus(UserStatus.PENDING).stream()
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
        for (StrategyType type : StrategyType.values()) {
            boolean before = previous.strategies().get(type).enabled();
            boolean after = saved.strategies().get(type).enabled();
            if (before != after) strategyChanges.put(type.name(), after);
        }
        if (!strategyChanges.isEmpty()) payload.put("strategies", strategyChanges);
        return payload;
    }
}
