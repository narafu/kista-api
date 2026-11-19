package com.kista.shared.platform.persistence;

import org.flywaydb.core.Flyway;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;

// 서비스 소유 스키마별 Flyway 이력을 하나의 FlywayMigrationInitializer로 순서대로 migrate한다.
// Boot가 DB 초기화 완료 시점을 이 빈 타입으로 감지하므로 JPA는 migrate 뒤에 시작한다(spring.flyway.enabled=false 전제)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KistaFlywayProperties.class)
public class KistaFlywayConfig {

    @Bean
    FlywayMigrationInitializer kistaFlywayMigrationInitializer(DataSource dataSource, KistaFlywayProperties properties) {
        List<Flyway> flyways = properties.migrations().stream()
                .map(m -> Flyway.configure()
                        .dataSource(dataSource)
                        .locations(m.locations())
                        .table(m.table())
                        .defaultSchema(m.schema())
                        .schemas(m.schema())
                        .baselineOnMigrate(false)
                        .validateOnMigrate(true)
                        .load())
                .toList();
        // 전략은 첫 Flyway를 받지만 목록 전부를 순서대로 migrate한다
        return new FlywayMigrationInitializer(flyways.getFirst(), ignored -> flyways.forEach(Flyway::migrate));
    }
}
