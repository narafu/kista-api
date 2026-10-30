package com.kista.api.user.adapter.out.persistence.user;

import com.kista.api.user.domain.model.User;
import com.kista.api.web.RootDataJpaTestBase;
import com.kista.shared.kernel.NotificationChannel;
import com.kista.shared.kernel.UserRole;
import com.kista.shared.kernel.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// telegramBotToken·email은 AesStringConverter가 암호화 — 컬럼 원문은 평문이 아니고 어댑터로 읽으면 평문이 복원된다
@Import(UserPersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD)
class UserPersistenceAdapterTest extends RootDataJpaTestBase {

    @Autowired UserPersistenceAdapter adapter;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    private User user(String email, String token) {
        return new User(UUID.randomUUID(), "kakao-" + UUID.randomUUID(), "닉네임", email, UserStatus.ACTIVE, UserRole.USER,
                token, "chat-1", "bot-username", null, null, NotificationChannel.TELEGRAM);
    }

    @Test
    void emailAndTokenEncryptedAtRestAndDecryptedOnRead() {
        User saved = adapter.save(user("user@example.com", "plain-bot-token"));
        em.flush();
        em.clear();

        var raw = jdbc.queryForMap("SELECT email, telegram_bot_token FROM users WHERE id = ?", saved.id());
        assertThat(raw.get("email").toString()).isNotEqualTo("user@example.com");
        assertThat(raw.get("telegram_bot_token").toString()).isNotEqualTo("plain-bot-token");

        User found = adapter.findById(saved.id()).orElseThrow();
        assertThat(found.email()).isEqualTo("user@example.com");
        assertThat(found.telegramBotToken()).isEqualTo("plain-bot-token");
    }

    @Test
    void nullEmailAndTokenRoundTripAsNull() {
        User saved = adapter.save(user(null, null));
        em.flush();
        em.clear();

        assertThat(adapter.findById(saved.id()).orElseThrow().email()).isNull();
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, saved.id())).isNull();
    }
}
