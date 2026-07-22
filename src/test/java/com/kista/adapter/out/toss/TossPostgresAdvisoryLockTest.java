package com.kista.adapter.out.toss;

import com.kista.domain.model.toss.TossApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Toss PostgreSQL session advisory lock")
class TossPostgresAdvisoryLockTest {

    private static final long LOCK_KEY = -7_331_190_212_311_442_101L;

    @Test
    @DisplayName("획득한 세션의 같은 Connection으로 unlock한 뒤 close한다")
    void acquiredHandle_unlocksOnSameConnection_thenCloses() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement acquire = mock(PreparedStatement.class);
        PreparedStatement unlock = mock(PreparedStatement.class);
        ResultSet acquired = booleanResult(true);
        ResultSet released = booleanResult(true);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(acquire);
        when(connection.prepareStatement("SELECT pg_advisory_unlock(?)")).thenReturn(unlock);
        when(acquire.executeQuery()).thenReturn(acquired);
        when(unlock.executeQuery()).thenReturn(released);
        TossPostgresAdvisoryLock advisoryLock = new TossPostgresAdvisoryLock(dataSource);

        Optional<TossTokenIssuanceLock.Handle> handle = advisoryLock.tryAcquire(LOCK_KEY);

        assertThat(handle).isPresent();
        verify(connection, never()).close();

        handle.orElseThrow().close();

        var order = inOrder(acquire, unlock, connection);
        order.verify(acquire).setQueryTimeout(1);
        order.verify(acquire).setLong(1, LOCK_KEY);
        order.verify(acquire).executeQuery();
        order.verify(unlock).setQueryTimeout(1);
        order.verify(unlock).setLong(1, LOCK_KEY);
        order.verify(unlock).executeQuery();
        order.verify(connection).close();
    }

    @Test
    @DisplayName("다른 세션이 owner이면 획득 실패 Connection을 즉시 close한다")
    void unavailableLock_closesAttemptConnection() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement acquire = mock(PreparedStatement.class);
        ResultSet unavailable = booleanResult(false);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(acquire);
        when(acquire.executeQuery()).thenReturn(unavailable);
        TossPostgresAdvisoryLock advisoryLock = new TossPostgresAdvisoryLock(dataSource);

        Optional<TossTokenIssuanceLock.Handle> handle = advisoryLock.tryAcquire(LOCK_KEY);

        assertThat(handle).isEmpty();
        verify(connection).close();
    }

    @Test
    @DisplayName("획득 SQL 오류는 Connection을 close하고 fail-closed 한다")
    void acquisitionSqlFailure_closesConnectionAndFailsClosed() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_lock(?)"))
                .thenThrow(new SQLException("postgres unavailable"));
        TossPostgresAdvisoryLock advisoryLock = new TossPostgresAdvisoryLock(dataSource);

        assertThatThrownBy(() -> advisoryLock.tryAcquire(LOCK_KEY))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("advisory lock");
        verify(connection).abort(any(Executor.class));
        verify(connection).close();
    }

    @Test
    @DisplayName("lock 획득 뒤 응답 정리 실패 시 ownership 불명 session을 abort한다")
    void acquisitionResponseCleanupFailure_abortsUncertainOwnerSession() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement acquire = mock(PreparedStatement.class);
        ResultSet acquired = booleanResult(true);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(acquire);
        when(acquire.executeQuery()).thenReturn(acquired);
        doThrow(new SQLException("response cleanup failed")).when(acquired).close();
        TossPostgresAdvisoryLock advisoryLock = new TossPostgresAdvisoryLock(dataSource);

        assertThatThrownBy(() -> advisoryLock.tryAcquire(LOCK_KEY))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("advisory lock");

        var order = inOrder(connection);
        order.verify(connection).abort(any(Executor.class));
        order.verify(connection).close();
    }

    @Test
    @DisplayName("unlock 실패 시 잠긴 세션을 풀에 반환하지 않고 abort한다")
    void unlockFailure_abortsConnectionBeforeClose() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement acquire = mock(PreparedStatement.class);
        PreparedStatement unlock = mock(PreparedStatement.class);
        ResultSet acquired = booleanResult(true);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(acquire);
        when(connection.prepareStatement("SELECT pg_advisory_unlock(?)")).thenReturn(unlock);
        when(acquire.executeQuery()).thenReturn(acquired);
        when(unlock.executeQuery()).thenThrow(new SQLException("unlock failed"));
        TossPostgresAdvisoryLock advisoryLock = new TossPostgresAdvisoryLock(dataSource);
        TossTokenIssuanceLock.Handle handle = advisoryLock.tryAcquire(LOCK_KEY).orElseThrow();

        assertThatThrownBy(handle::close)
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("unlock");
        verify(connection).abort(any(Executor.class));
        verify(connection).close();
    }

    @Test
    @DisplayName("전용 pool session 한도에 도달하면 DataSource에서 추가 Connection을 빌리지 않는다")
    void sessionCapacityExhausted_returnsUnavailableWithoutBorrowing() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection firstConnection = lockableConnection();
        Connection secondConnection = lockableConnection();
        when(dataSource.getConnection()).thenReturn(firstConnection, secondConnection);
        TossPostgresAdvisoryLock advisoryLock = new TossPostgresAdvisoryLock(dataSource, 2);

        TossTokenIssuanceLock.Handle first = advisoryLock.tryAcquire(1L).orElseThrow();
        TossTokenIssuanceLock.Handle second = advisoryLock.tryAcquire(2L).orElseThrow();

        assertThat(advisoryLock.tryAcquire(3L)).isEmpty();
        verify(dataSource, times(2)).getConnection();

        first.close();
        second.close();
    }

    private Connection lockableConnection() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(any())).thenReturn(statement);
        when(statement.executeQuery()).thenAnswer(invocation -> booleanResult(true));
        return connection;
    }

    private ResultSet booleanResult(boolean value) throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBoolean(1)).thenReturn(value);
        return resultSet;
    }
}
