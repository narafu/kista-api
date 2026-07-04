package com.kista.application.service.broker;

import com.kista.domain.model.account.Account;
import com.kista.domain.port.out.broker.BrokerConnectionTestPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// 증권사별 연결테스트 포트 라우터 — supports()로 Map 빌드, broker enum으로 조회
@Slf4j
@Component
public class BrokerConnectionTesters {

    private final Map<Account.Broker, BrokerConnectionTestPort> testers;

    BrokerConnectionTesters(List<BrokerConnectionTestPort> list) {
        testers = list.stream()
                .collect(Collectors.toMap(BrokerConnectionTestPort::supports, Function.identity()));
        log.info("BrokerConnectionTesters 초기화: {}", testers.keySet());
    }

    // 미지원 증권사면 IllegalArgumentException → GlobalExceptionHandler 400
    public BrokerConnectionTestPort of(Account.Broker broker) {
        BrokerConnectionTestPort tester = testers.get(broker);
        if (tester == null) {
            throw new IllegalArgumentException("지원하지 않는 증권사: " + broker);
        }
        return tester;
    }
}
