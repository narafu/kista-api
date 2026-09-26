package com.kista.broker.application.service;

import com.kista.broker.application.port.output.BrokerAdapterPort;
import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.sharedkernel.Broker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

// 증권사 어댑터 레지스트리 — BrokerAccountRef.broker()로 BrokerAdapterPort 조회 후 Capability 캐스팅
@Slf4j
@Component
public class BrokerAdapterRegistry {

    private final Map<Broker, BrokerAdapterPort> registry;

    BrokerAdapterRegistry(List<BrokerAdapterPort> adapters) {
        registry = BrokerRegistrySupport.buildMap(adapters, BrokerAdapterPort::supports);
        log.info("BrokerAdapterRegistry 초기화: {}", registry.keySet());
    }

    // 지원하지 않으면 IllegalArgumentException — GlobalExceptionHandler → 400
    public <T> T require(BrokerAccountRef account, Class<T> capability) {
        BrokerAdapterPort adapter = BrokerRegistrySupport.requireByBroker(registry, account.broker());
        if (!capability.isInstance(adapter)) {
            throw new IllegalArgumentException(
                    account.broker() + " 브로커는 " + capability.getSimpleName() + "를 지원하지 않습니다");
        }
        return capability.cast(adapter);
    }
}
