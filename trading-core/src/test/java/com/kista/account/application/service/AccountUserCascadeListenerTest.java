package com.kista.account.application.service;

import com.kista.account.application.port.output.AccountPort;
import com.kista.sharedkernel.UserDeletedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountUserCascadeListener 단위 테스트")
class AccountUserCascadeListenerTest {

    @Mock AccountPort accountPort;

    @InjectMocks AccountUserCascadeListener listener;

    @Test
    @DisplayName("UserDeletedEvent를 받으면 해당 사용자 계좌를 소프트 삭제한다")
    void onUserDeleted_softDeletesAccounts() {
        UUID userId = UUID.randomUUID();

        listener.onUserDeleted(new UserDeletedEvent(userId));

        verify(accountPort).deleteByUserId(userId);
    }
}
