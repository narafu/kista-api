package com.kista.domain.port.in;

import java.util.List;
import java.util.UUID;

public interface UpdateStrategySuggestionsUseCase {
    void update(UpdateStrategySuggestionsCommand command);

    record UpdateStrategySuggestionsCommand(UUID userId, List<String> suggestions) {}
}
