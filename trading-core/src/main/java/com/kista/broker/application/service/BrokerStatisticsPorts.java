package com.kista.broker.application.service;

import com.kista.broker.application.port.output.BrokerStatisticsPort;
import com.kista.sharedkernel.Broker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

// 증권사별 통계 포트 레지스트리 — supports()로 Map 빌드, broker enum으로 조회(BrokerConnectionTesters 패턴)
@Slf4j
@Component
public class BrokerStatisticsPorts {

    private final Map<Broker, BrokerStatisticsPort> ports;

    BrokerStatisticsPorts(List<BrokerStatisticsPort> list) {
        ports = list.stream().collect(Collectors.toMap(BrokerStatisticsPort::supports, Function.identity()));
        log.info("BrokerStatisticsPorts 초기화: {}", ports.keySet());
    }

    // 통계 기능을 지원하지 않는 증권사(KIS/MOCK 등)는 빈 Optional — 호출측이 400 사유를 결정한다
    public Optional<BrokerStatisticsPort> find(Broker broker) {
        return Optional.ofNullable(ports.get(broker));
    }
}
