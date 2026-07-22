package com.kista.adapter.out.toss;

import com.kista.domain.model.toss.TossApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Semaphore;

@Component
class TossPostgresAdvisoryLock implements TossTokenIssuanceLock {

    private static final String TRY_LOCK_SQL = "SELECT pg_try_advisory_lock(?)";
    private static final String UNLOCK_SQL = "SELECT pg_advisory_unlock(?)";

    private final DataSource dataSource;
    private final Semaphore sessionPermits;

    @Autowired
    TossPostgresAdvisoryLock(TossAdvisoryLockDataSource lockDataSource) {
        this(lockDataSource.dataSource(), TossAdvisoryLockDataSourceConfig.MAXIMUM_POOL_SIZE);
    }

    TossPostgresAdvisoryLock(DataSource dataSource) {
        this(dataSource, TossAdvisoryLockDataSourceConfig.MAXIMUM_POOL_SIZE);
    }

    TossPostgresAdvisoryLock(DataSource dataSource, int maximumSessions) {
        this.dataSource = dataSource;
        this.sessionPermits = new Semaphore(maximumSessions, true);
    }

    @Override
    public Optional<Handle> tryAcquire(long lockKey) {
        if (!sessionPermits.tryAcquire()) {
            return Optional.empty();
        }
        Connection connection = null;
        boolean handleOwnsPermit = false;
        try {
            connection = dataSource.getConnection();
            if (!queryBoolean(connection, TRY_LOCK_SQL, lockKey)) {
                connection.close();
                return Optional.empty();
            }
            handleOwnsPermit = true;
            return Optional.of(new JdbcHandle(connection, lockKey, sessionPermits));
        } catch (SQLException exception) {
            abortAfterAcquisitionFailure(connection, exception);
            throw new TossApiException("Toss PostgreSQL advisory lock 획득 실패", exception);
        } finally {
            if (!handleOwnsPermit) {
                sessionPermits.release();
            }
        }
    }

    private static boolean queryBoolean(Connection connection, String sql, long lockKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(1);
            statement.setLong(1, lockKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("PostgreSQL advisory lock 응답이 비었습니다");
                }
                return resultSet.getBoolean(1);
            }
        }
    }

    private static void abortAfterAcquisitionFailure(Connection connection, SQLException cause) {
        if (connection == null) {
            return;
        }
        try {
            connection.abort(Runnable::run);
        } catch (SQLException abortFailure) {
            cause.addSuppressed(abortFailure);
        }
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            cause.addSuppressed(closeFailure);
        }
    }

    private static final class JdbcHandle implements Handle {
        private final Connection connection;
        private final long lockKey;
        private final Semaphore sessionPermits;
        private final AtomicBoolean closed = new AtomicBoolean();

        private JdbcHandle(Connection connection, long lockKey, Semaphore sessionPermits) {
            this.connection = connection;
            this.lockKey = lockKey;
            this.sessionPermits = sessionPermits;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }

            SQLException failure = null;
            try {
                if (!queryBoolean(connection, UNLOCK_SQL, lockKey)) {
                    failure = new SQLException("PostgreSQL advisory lock owner가 아닌 세션에서 unlock 시도");
                }
            } catch (SQLException exception) {
                failure = exception;
            }

            if (failure != null) {
                try {
                    connection.abort(Runnable::run);
                } catch (SQLException abortFailure) {
                    failure.addSuppressed(abortFailure);
                }
            }
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            sessionPermits.release();

            if (failure != null) {
                throw new TossApiException("Toss PostgreSQL advisory lock unlock 실패", failure);
            }
        }
    }
}
