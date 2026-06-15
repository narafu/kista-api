package com.kista.adapter.out.persistence.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kista.domain.model.admin.AuditLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogPersistenceAdapterTest {

    @Mock AuditLogJpaRepository repo;
    AuditLogPersistenceAdapter adapter;

    static final UUID ADMIN_ID = UUID.randomUUID();
    static final UUID TARGET_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adapter = new AuditLogPersistenceAdapter(repo, new ObjectMapper());
    }

    @Test
    void log_saves_entity_with_serialized_payload() {
        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        when(repo.save(any(AuditLogEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        adapter.log(ADMIN_ID, "DELETE_USER", "USER", TARGET_ID, Map.of("key", "val"));

        verify(repo).save(captor.capture());
        AuditLogEntity saved = captor.getValue();
        assertThat(saved.getAdminId()).isEqualTo(ADMIN_ID);
        assertThat(saved.getAction()).isEqualTo("DELETE_USER");
        assertThat(saved.getPayload()).contains("key");
    }

    @Test
    void findById_returns_domain_when_found() {
        UUID entityId = UUID.randomUUID();
        AuditLogEntity entity = new AuditLogEntity(entityId, ADMIN_ID, "ACTION", "USER", TARGET_ID, null);
        when(repo.findById(entityId)).thenReturn(Optional.of(entity));

        AuditLog result = adapter.findById(entityId);

        assertThat(result.id()).isEqualTo(entityId);
        assertThat(result.adminId()).isEqualTo(ADMIN_ID);
        assertThat(result.action()).isEqualTo("ACTION");
        assertThat(result.targetType()).isEqualTo("USER");
        assertThat(result.targetId()).isEqualTo(TARGET_ID);
    }

    @Test
    void findById_throws_when_not_found() {
        UUID missingId = UUID.randomUUID();
        when(repo.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.findById(missingId))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findAll_returns_mapped_list() {
        AuditLogEntity entity = new AuditLogEntity(UUID.randomUUID(), ADMIN_ID, "ACTION", "USER", TARGET_ID, null);
        when(repo.findTop100ByOrderByCreatedAtDesc()).thenReturn(List.of(entity));

        List<AuditLog> result = adapter.findAll();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().adminId()).isEqualTo(ADMIN_ID);
    }
}
