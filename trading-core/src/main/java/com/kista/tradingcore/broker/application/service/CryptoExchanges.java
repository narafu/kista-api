package com.kista.tradingcore.broker.application.service;

import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.broker.application.port.output.CryptoExchangePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

// 코인 거래소 포트 라우터 — supports()로 Map 빌드, broker enum으로 조회(BrokerConnectionTesters 패턴)
@Slf4j
@Component
public class CryptoExchanges {

    private final Map<Broker, CryptoExchangePort> ports;

    CryptoExchanges(List<CryptoExchangePort> list) {
        ports = list.stream().collect(Collectors.toMap(CryptoExchangePort::supports, Function.identity()));
        log.info("CryptoExchanges 초기화: {}", ports.keySet());
    }

    // 코인 거래소가 아니면 IllegalArgumentException → 400
    public CryptoExchangePort of(Broker broker) {
        return Optional.ofNullable(ports.get(broker))
                .orElseThrow(() -> new IllegalArgumentException("코인 거래소가 아닌 증권사입니다: " + broker));
    }
}
