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
    void save_preservesExistingTokensOnSamePlatform() {
        UUID userId = UUID.randomUUID();
        when(repository.findByUserIdAndToken(userId, "token-new")).thenReturn(Optional.empty());

        adapter.save(userId, "token-new", " web ");

        ArgumentCaptor<FcmDeviceTokenEntity> captor = ArgumentCaptor.forClass(FcmDeviceTokenEntity.class);
        verify(repository).deleteByToken("token-new");
        verify(repository).flush();
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getPlatform()).isEqualTo("WEB");
    }

    @Test
    void save_existingUserTokenIsIdempotent() {
        UUID userId = UUID.randomUUID();
        when(repository.findByUserIdAndToken(userId, "token-existing"))
                .thenReturn(Optional.of(FcmDeviceTokenEntity.of(userId, "token-existing", "WEB")));

        adapter.save(userId, "token-existing", "WEB");

        verify(repository, never()).deleteByToken(any());
        verify(repository, never()).flush();
        verify(repository, never()).save(any());
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
