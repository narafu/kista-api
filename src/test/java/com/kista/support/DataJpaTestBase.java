package com.kista.support;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.test.context.ActiveProfiles;

// @DataJpaTest + application-test.yml 로컬 PostgreSQL 공통 베이스
// 서브클래스는 이 클래스를 상속하는 것만으로 localhost:5432/kistadb_test 에 연결됨
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
