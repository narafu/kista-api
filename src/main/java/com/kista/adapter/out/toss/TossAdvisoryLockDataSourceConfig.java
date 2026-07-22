package com.kista.adapter.out.toss;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TossAdvisoryLockDataSourceConfig {

    static final int MAXIMUM_POOL_SIZE = 2;
    static final long CONNECTION_TIMEOUT_MILLIS = 1_000L;
    static final long VALIDATION_TIMEOUT_MILLIS = 1_000L;

    @Bean(destroyMethod = "close")
    TossAdvisoryLockDataSource tossAdvisoryLockDataSource(DataSourceProperties properties) {
        HikariDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        dataSource.setPoolName("HikariPool-TossAdvisoryLock");
        dataSource.setMaximumPoolSize(MAXIMUM_POOL_SIZE);
        dataSource.setMinimumIdle(0);
        dataSource.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        dataSource.setValidationTimeout(VALIDATION_TIMEOUT_MILLIS);
        return new TossAdvisoryLockDataSource(dataSource);
    }
}

final class TossAdvisoryLockDataSource implements AutoCloseable {
    private final HikariDataSource delegate;

    TossAdvisoryLockDataSource(HikariDataSource delegate) {
        this.delegate = delegate;
    }

    HikariDataSource dataSource() {
        return delegate;
    }

    @Override
    public void close() {
        delegate.close();
    }
}
