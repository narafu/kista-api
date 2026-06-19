package com.kista.adapter.out.persistence.auth;

import com.kista.domain.model.auth.RefreshToken;
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
                "Mozilla/5.0", Instant.now().plusSeconds(432000), null, null);
        adapter.save(token);
        verify(repository).save(any(RefreshTokenEntity.class));
    }

    @Test
    void findByTokenHash_found_returnsDomain() {
        UUID userId = UUID.randomUUID();
        RefreshTokenEntity entity = RefreshTokenEntity.from(
                new RefreshToken(null, userId, "abc123", null, Instant.now().plusSeconds(1000), null, null));
        given(repository.findByTokenHash("abc123")).willReturn(Optional.of(entity));

        Optional<RefreshToken> result = adapter.findByTokenHash("abc123");

        assertThat(result).isPresent();
        assertThat(result.get().userId()).isEqualTo(userId);
        assertThat(result.get().rotatedAt()).isNull();
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

    // markRotated — 조건부 update 위임 검증
    @Test
    void markRotated_delegatesToRepository() {
        String hash = "somehash";
        Instant now = Instant.now();
        given(repository.markRotated(hash, now)).willReturn(1);

        int result = adapter.markRotated(hash, now);

        assertThat(result).isEqualTo(1);
        verify(repository).markRotated(hash, now);
    }

    // 두 번째 markRotated 호출 시 0 반환 (rotated_at IS NULL 조건 불충족)
    @Test
    void markRotated_alreadyRotated_returnsZero() {
        String hash = "alreadyRotated";
        Instant now = Instant.now();
        given(repository.markRotated(hash, now)).willReturn(0);

        int result = adapter.markRotated(hash, now);

        assertThat(result).isZero();
    }

    // deleteAllRotatedBefore — 임계값 위임 검증
    @Test
    void deleteAllRotatedBefore_delegatesToRepository() {
        Instant threshold = Instant.now().minusSeconds(120);
        given(repository.deleteAllByRotatedAtBefore(threshold)).willReturn(3);

        int result = adapter.deleteAllRotatedBefore(threshold);

        assertThat(result).isEqualTo(3);
        verify(repository).deleteAllByRotatedAtBefore(threshold);
    }
}
