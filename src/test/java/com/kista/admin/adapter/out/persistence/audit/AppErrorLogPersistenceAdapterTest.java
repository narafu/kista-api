package com.kista.admin.adapter.out.persistence.audit;

import com.kista.admin.domain.model.AppErrorLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.transaction.PlatformTransactionManager;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppErrorLogPersistenceAdapterTest {

    @Mock AppErrorLogJpaRepository repo;
    @Mock PlatformTransactionManager txManager; // TransactionTemplate이 getTransaction/commit만 호출 — mock으로 충분
    AppErrorLogPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new AppErrorLogPersistenceAdapter(repo, txManager);
    }

    @Test
    void save_clientError_stores_entity_without_exception() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<AppErrorLogEntity> captor = ArgumentCaptor.forClass(AppErrorLogEntity.class);

        adapter.save("TypeError", "cannot read property", "at foo()\nat bar()", java.util.Map.of("pathname", "/login"));

        verify(repo).save(captor.capture());
        AppErrorLogEntity saved = captor.getValue();
        assertThat(saved.getErrorType()).isEqualTo("TypeError");
        assertThat(saved.getMessage()).isEqualTo("cannot read property");
        assertThat(saved.getStackTrace()).isEqualTo("at foo()\nat bar()");
        assertThat(saved.getContext()).containsEntry("pathname", "/login");
    }

    @Test
    void save_clientErrorOverload_swallowsRepoFailure() {
        // 이 메서드는 저장 실패해도 예외를 던지지 않는다는 계약(호출부 4곳에서 이관된 격리 책임) 검증
        doThrow(new RuntimeException("db down")).when(repo).save(any());

        assertThatCode(() -> adapter.save("TypeError", "cannot read property", "at foo()", java.util.Map.of("pathname", "/login")))
                .doesNotThrowAnyException();
    }

    @Test
    void findRecent_returns_mapped_list() {
        AppErrorLogEntity entity = new AppErrorLogEntity(
                null, "KisApiException", "KIS 오류", "stack", Map.of("caller", "TradingService"), null
        );
        Instant from = Instant.EPOCH;
        Instant to = Instant.now();
        when(repo.findTopNByCreatedAtBetween(from, to, 50)).thenReturn(List.of(entity));

        List<AppErrorLog> result = adapter.findRecent(50, from, to);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().errorType()).isEqualTo("KisApiException");
        assertThat(result.getFirst().context()).containsKey("caller");
    }
}
