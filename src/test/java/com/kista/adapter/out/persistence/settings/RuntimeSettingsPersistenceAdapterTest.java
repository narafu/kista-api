package com.kista.adapter.out.persistence.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.kista.domain.model.settings.RuntimeSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeSettingsPersistenceAdapterTest {

    @Mock RuntimeSettingsJpaRepository repository; // 영속 저장소 대역

    private RuntimeSettingsPersistenceAdapter adapter; // 테스트 대상
    private ObjectMapper objectMapper; // 저장 JSON 생성기

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new Jdk8Module());
        adapter = new RuntimeSettingsPersistenceAdapter(repository, objectMapper);
    }

    @Test
    void loadReturnsSafeDefaultsWhenRowIsMissing() {
        when(repository.findById(RuntimeSettingsPersistenceAdapter.SETTING_KEY)).thenReturn(Optional.empty());

        assertThat(adapter.load()).isEqualTo(RuntimeSettings.defaults());
    }

    @Test
    void saveAndLoadRoundTripTypedSettings() {
        RuntimeSettings settings = RuntimeSettings.defaults();
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));

        RuntimeSettings saved = adapter.save(settings);

        assertThat(saved).isEqualTo(settings);
        verify(repository).save(org.mockito.ArgumentMatchers.argThat(entity ->
                entity.getSettingKey().equals(RuntimeSettingsPersistenceAdapter.SETTING_KEY)
                        && entity.getSettingValue().contains("approvalRequired")));
    }

    @Test
    void loadDeserializesStoredJsonIntoTypedSettings() throws Exception {
        RuntimeSettings expected = RuntimeSettings.defaults();
        RuntimeSettingsEntity entity = new RuntimeSettingsEntity(
                RuntimeSettingsPersistenceAdapter.SETTING_KEY, objectMapper.writeValueAsString(expected));
        when(repository.findById(RuntimeSettingsPersistenceAdapter.SETTING_KEY)).thenReturn(Optional.of(entity));

        assertThat(adapter.load()).isEqualTo(expected);
    }

    @Test
    void loadForUpdateCreatesMissingSingletonBeforeLockingIt() throws Exception {
        RuntimeSettings defaults = RuntimeSettings.defaults();
        RuntimeSettingsEntity entity = new RuntimeSettingsEntity(
                RuntimeSettingsPersistenceAdapter.SETTING_KEY, objectMapper.writeValueAsString(defaults));
        when(repository.findBySettingKeyForUpdate(RuntimeSettingsPersistenceAdapter.SETTING_KEY))
                .thenReturn(Optional.of(entity));

        assertThat(adapter.loadForUpdate()).isEqualTo(defaults);

        verify(repository).insertIfMissing(
                org.mockito.ArgumentMatchers.eq(RuntimeSettingsPersistenceAdapter.SETTING_KEY),
                org.mockito.ArgumentMatchers.contains("approvalRequired"));
        verify(repository).findBySettingKeyForUpdate(RuntimeSettingsPersistenceAdapter.SETTING_KEY);
    }
}
