package com.kista.api.user.adapter.out.persistence.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "user_settings", schema = "public")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class UserSettingsJpaEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID userId; // 사용자 식별자 — PK이자 FK (users.id)

    // 운용전략 추천 목록 — null이면 미설정(어댑터가 기본값으로 채움). Hibernate가 jsonb로 직접 매핑
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "strategy_suggestions", columnDefinition = "jsonb")
    private List<String> strategySuggestions;
}
