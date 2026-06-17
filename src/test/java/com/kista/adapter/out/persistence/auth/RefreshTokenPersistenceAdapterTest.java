package com.kista.adapter.out.persistence.auth;

import com.kista.domain.model.auth.RefreshToken;
import com.kista.domain.port.out.RefreshTokenPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RefreshTokenPersistenceAdapterTest {

    @Mock RefreshTokenJpaRepository repository;
    @InjectMocks RefreshTokenPersistenceAdapter adapter;

    @Test
    void save_persistsEntity() {
        RefreshToken token = new RefreshToken(null, UUID.randomUUID(), "hash64chars",
                "Mozilla/5.0", Instant.now().plusSeconds(432000), null);
        adapter.save(token);
        verify(repository).save(any(RefreshTokenEntity.class));
    }

    @Test
    void findByTokenHash_found_returnsDomain() {
        UUID userId = UUID.randomUUID();
        RefreshTokenEntity entity = RefreshTokenEntity.from(
                new RefreshToken(null, userId, "abc123", null, Instant.now().plusSeconds(1000), null));
        given(repository.findByTokenHash("abc123")).willReturn(Optional.of(entity));

        Optional<RefreshToken> result = adapter.findByTokenHash("abc123");

        assertThat(result).isPresent();
        assertThat(result.get().userId()).isEqualTo(userId);
    }

    @Test
    void findByTokenHash_notFound_returnsEmpty() {
        given(repository.findByTokenHash("unknown")).willReturn(Optional.empty());
        assertThat(adapter.findByTokenHash("unknown")).isEmpty();
    }

    @Test
    void deleteByTokenHash_callsRepository() {
        adapter.deleteByTokenHash("somehash");
        verify(repository).deleteByTokenHash("somehash");
    }

    @Test
    void deleteAllByUserId_callsRepository() {
        UUID userId = UUID.randomUUID();
        adapter.deleteAllByUserId(userId);
        verify(repository).deleteAllByUserId(userId);
    }
}
