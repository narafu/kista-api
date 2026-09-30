package com.kista.trading.application.service;

import com.kista.account.application.port.output.BrokerEnabledPort;
import com.kista.sharedkernel.Broker;
import com.kista.sharedkernel.StrategyCreationSettings;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.TradingPolicySettings;
import com.kista.trading.application.port.output.StrategyCreationPolicyPort;
import com.kista.trading.application.port.output.TradingPolicySettingsPort;
import com.kista.trading.application.usecase.TradingPolicyUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

// 매매 런타임 정책의 소유 서비스 — 정책이 지키는 불변식(계좌 등록 차단·전략 생성 차단)이 이 프로세스에 있으므로
// 저장소도 여기(trading 스키마)가 갖는다. root admin은 편집 UI로서 TradingPolicyUseCase를 내부 API로 호출할 뿐이다.
// account가 정의한 BrokerEnabledPort와 trading 자신의 StrategyCreationPolicyPort를 같은 저장소 위에서 구현한다.
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class TradingPolicyService implements TradingPolicyUseCase, BrokerEnabledPort, StrategyCreationPolicyPort {

    private final TradingPolicySettingsPort settingsPort; // 정책 영속화 포트 (trading.trading_runtime_settings)

    @Override
    @Transactional(readOnly = true)
    public TradingPolicySettings getPolicy() {
        return settingsPort.load();
    }

    @Override
    public TradingPolicySettings replacePolicy(TradingPolicySettings settings) {
        TradingPolicySettings saved = settingsPort.save(settings);
        log.info("매매 런타임 정책 교체: brokers={}, strategies={}", saved.brokers(), enabledFlags(saved));
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean enabled(Broker broker) {
        return settingsPort.load().brokerEnabled(broker);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StrategyCreationSettings> find(StrategyType type) {
        return Optional.ofNullable(settingsPort.load().strategy(type));
    }

    // 로그용 — 전략별 enabled만 요약
    private static String enabledFlags(TradingPolicySettings settings) {
        StringBuilder sb = new StringBuilder();
        settings.strategies().forEach((type, s) -> sb.append(type).append('=').append(s.enabled()).append(' '));
        return sb.toString().trim();
    }
}
