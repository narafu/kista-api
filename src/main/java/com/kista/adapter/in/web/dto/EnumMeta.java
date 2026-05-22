package com.kista.adapter.in.web.dto;

public record EnumMeta(
        String code,        // enum name() 값
        String label,       // 한국어 표시 이름
        String description  // 설명 (null 가능)
) {}
