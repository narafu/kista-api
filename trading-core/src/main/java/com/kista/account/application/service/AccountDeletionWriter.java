package com.kista.account.application.service;

import com.kista.account.application.event.AccountDeletedEvent;
import com.kista.account.application.port.output.AccountPort;
import com.kista.broker.application.port.output.BrokerTokenCachePort;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

// AccountService.delete()의 증권사 주문 취소 HTTP를 트랜잭션 밖으로 빼기 위한 DB 쓰기 전용 헬퍼
// 메서드는 반드시 public — Spring 기본 proxy 모드는 non-public @Transactional을 무시(no-op)함
@Service
@RequiredArgsConstructor
class AccountDeletionWriter {

    private final AccountPort accountPort;
    private final BrokerTokenCachePort brokerTokenCachePort;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void delete(UUID accountId) {
        brokerTokenCachePort.deleteByAccountIds(List.of(accountId));
        accountPort.delete(accountId);
        // 커밋 후 발행 — trading 리스너가 전략·사이클·포지션을 독립적으로 정리(EPR 재시도 보장)
        eventPublisher.publishEvent(new AccountDeletedEvent(accountId));
    }
}
