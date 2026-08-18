package com.kista.domain.model.finance;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Market {
    DOMESTIC("국내"),
    GLOBAL("해외");

    private final String label;
}
