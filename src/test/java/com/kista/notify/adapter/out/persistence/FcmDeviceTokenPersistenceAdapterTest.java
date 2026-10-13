package com.kista.notify.adapter.out.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FcmDeviceTokenPersistenceAdapterTest {

    @Mock FcmDeviceTokenJpaRepository repository;

    FcmDeviceTokenPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new FcmDeviceTokenPersistenceAdapter(repository);
    }

    @Test
    void save_normalizesPlatformAndDelegatesToUpsert() {
        UUID userId = UUID.randomUUID();

        adapter.save(userId, "token-new", " web ");

        // 신규 행 PK는 어댑터가 UUID v7로 만들어 넘긴다 (native upsert라 JPA 생성기를 못 탄다)
        verify(repository).upsert(argThat(id -> id.version() == 7), eq(userId), eq("token-new"), eq("WEB"));
    }

    @Test
    void save_invalidPlatform_throws() {
        assertThatThrownBy(() -> adapter.save(UUID.randomUUID(), "token", "desktop"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("허용값: WEB, ANDROID, IOS");
        verifyNoInteractions(repository);
    }

    @Test
    void delete_alreadyDeletedByConcurrentThread_swallowsOptimisticLockException() {
        UUID userId = UUID.randomUUID();
        doThrow(new ObjectOptimisticLockingFailureException(FcmDeviceTokenEntity.class, "token-a"))
                .when(repository).deleteByUserIdAndToken(userId, "token-a");

        adapter.delete(userId, "token-a");

        verify(repository).deleteByUserIdAndToken(userId, "token-a");
    }

    @Test
    void deleteAllByUserId_delegatesToRepository() {
        UUID userId = UUID.randomUUID();

        adapter.deleteAllByUserId(userId);

        verify(repository).deleteAllByUserId(userId);
    }

    @Test
    void findTokensByUserId_returnsDistinctTokens() {
        UUID userId = UUID.randomUUID();
        when(repository.findAllByUserId(userId)).thenReturn(List.of(
                FcmDeviceTokenEntity.of(userId, "token-a", "WEB"),
                FcmDeviceTokenEntity.of(userId, "token-a", "WEB"),
                FcmDeviceTokenEntity.of(userId, "token-b", "IOS")
        ));

        assertThat(adapter.findTokensByUserId(userId)).containsExactly("token-a", "token-b");
    }
}
