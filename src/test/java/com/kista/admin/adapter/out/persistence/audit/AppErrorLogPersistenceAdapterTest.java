package com.kista.admin.adapter.out.persistence.audit;

import tools.jackson.databind.ObjectMapper;
import com.kista.admin.domain.model.AppErrorLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppErrorLogPersistenceAdapterTest {

    @Mock AppErrorLogJpaRepository repo;
    AppErrorLogPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new AppErrorLogPersistenceAdapter(repo, new ObjectMapper());
    }

    @Test
    void save_stores_entity_with_all_fields() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<AppErrorLogEntity> captor = ArgumentCaptor.forClass(AppErrorLogEntity.class);

        adapter.save(new RuntimeException("테스트 오류"), "TradingOpenScheduler");

        verify(repo).save(captor.capture());
        AppErrorLogEntity saved = captor.getValue();
        assertThat(saved.getErrorType()).isEqualTo("RuntimeException");
        assertThat(saved.getMessage()).isEqualTo("테스트 오류");
        assertThat(saved.getStackTrace()).isNotBlank();
        assertThat(saved.getContext()).contains("TradingOpenScheduler");
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
        assertThat(saved.getContext()).contains("/login");
    }

    @Test
    void save_exceptionOverload_swallowsRepoFailure() {
        // 이 메서드는 저장 실패해도 예외를 던지지 않는다는 계약(호출부 4곳에서 이관된 격리 책임) 검증
        doThrow(new RuntimeException("db down")).when(repo).save(any());

        assertThatCode(() -> adapter.save(new RuntimeException("테스트 오류"), "TradingOpenScheduler"))
                .doesNotThrowAnyException();
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
                null, "KisApiException", "KIS 오류", "stack", "{\"caller\":\"TradingService\"}", null
        );
        when(repo.findTopNByOrderByCreatedAtDesc(50)).thenReturn(List.of(entity));

        List<AppErrorLog> result = adapter.findRecent(50);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().errorType()).isEqualTo("KisApiException");
        assertThat(result.getFirst().context()).containsKey("caller");
    }
}
