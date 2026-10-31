package com.kista.api.finance.adapter.in.event;

import com.kista.api.finance.application.usecase.FinanceUserDataEraseUseCase;
import com.kista.shared.kernel.UserDeletedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FinanceUserCascadeListenerTest {

    @Mock FinanceUserDataEraseUseCase eraser;
    @InjectMocks FinanceUserCascadeListener listener;

    @Test
    void 탈퇴_이벤트는_finance_정리로_위임한다() {
        UUID userId = UUID.randomUUID();

        listener.onUserDeleted(new UserDeletedEvent(userId));

        verify(eraser).eraseUserData(userId);
    }

    // AFTER_COMMIT 시점엔 원본 트랜잭션이 끝나 있어 REQUIRES_NEW가 아니면 정리 쓰기가 커밋되지 않는다 — 조합을 고정
    @Test
    void AFTER_COMMIT_리스너는_REQUIRES_NEW_트랜잭션으로_실행된다() throws NoSuchMethodException {
        var method = FinanceUserCascadeListener.class.getMethod("onUserDeleted", UserDeletedEvent.class);

        assertThat(method.getAnnotation(TransactionalEventListener.class).phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(method.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
