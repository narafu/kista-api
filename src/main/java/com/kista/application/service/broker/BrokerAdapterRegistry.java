package com.kista.application.service.broker;

import com.kista.domain.model.account.Account;
import com.kista.domain.port.out.broker.BrokerAdapterPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

// 브로커 어댑터 레지스트리 — account.broker()로 BrokerAdapterPort 조회 후 Capability 캐스팅
@Slf4j
@Component
public class BrokerAdapterRegistry {

    private final Map<Account.Broker, BrokerAdapterPort> registry;

    BrokerAdapterRegistry(List<BrokerAdapterPort> adapters) {
        registry = adapters.stream()
                .collect(Collectors.toMap(BrokerAdapterPort::supports, Function.identity()));
        log.info("BrokerAdapterRegistry 초기화: {}", registry.keySet());
    }

    // 지원하지 않으면 IllegalArgumentException — GlobalExceptionHandler → 400
    public <T> T require(Account account, Class<T> capability) {
        BrokerAdapterPort adapter = getAdapter(account);
        if (!capability.isInstance(adapter)) {
            throw new IllegalArgumentException(
                    account.broker() + " 브로커는 " + capability.getSimpleName() + "를 지원하지 않습니다");
        }
        return capability.cast(adapter);
    }

    // 지원하지 않으면 Optional.empty() — 호출자가 fallback 처리
    public <T> Optional<T> find(Account account, Class<T> capability) {
        BrokerAdapterPort adapter = registry.get(account.broker());
        if (adapter == null || !capability.isInstance(adapter)) return Optional.empty();
        return Optional.of(capability.cast(adapter));
    }

    private BrokerAdapterPort getAdapter(Account account) {
        BrokerAdapterPort adapter = registry.get(account.broker());
        if (adapter == null) {
            throw new IllegalArgumentException("지원하지 않는 브로커: " + account.broker());
        }
        return adapter;
    }
}
