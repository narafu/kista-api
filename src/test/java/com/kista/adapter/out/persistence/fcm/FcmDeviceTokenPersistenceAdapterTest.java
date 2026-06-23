package com.kista.adapter.out.persistence.fcm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
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
    void save_normalizesPlatformBeforeReplacingToken() {
        UUID userId = UUID.randomUUID();
        when(repository.findByUserIdAndToken(userId, "token-new")).thenReturn(Optional.empty());

        adapter.save(userId, "token-new", " web ");

        verify(repository).deleteByUserIdAndPlatform(userId, "WEB");
        ArgumentCaptor<FcmDeviceTokenEntity> captor = ArgumentCaptor.forClass(FcmDeviceTokenEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getPlatform()).isEqualTo("WEB");
    }

    @Test
    void save_invalidPlatform_throws() {
        assertThatThrownBy(() -> adapter.save(UUID.randomUUID(), "token", "desktop"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("허용값: WEB, ANDROID, IOS");
        verifyNoInteractions(repository);
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
