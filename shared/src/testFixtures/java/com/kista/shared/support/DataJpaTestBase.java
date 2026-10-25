package com.kista.shared.support;

import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.test.context.ActiveProfiles;

// @DataJpaTest + application-test.yml 로컬 PostgreSQL 공통 베이스
// 서브클래스는 이 클래스를 상속하는 것만으로 localhost:5432/kistadb_test(integration 태스크는 kistadb_it)에 연결됨
@DataJpaTest
@Import(DataJpaTestBase.TestJpaAuditingConfig.class)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class DataJpaTestBase {

    @TestConfiguration
    @EnableJpaAuditing
    static class TestJpaAuditingConfig {
    }
}
