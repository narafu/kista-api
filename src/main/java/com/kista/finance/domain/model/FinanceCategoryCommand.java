package com.kista.finance.domain.model;

import java.util.UUID;

// 등록·수정 공용 — parentId/type은 생성 시에만 사용되고 수정 시엔 무시된다(카테고리 계층·타입은 생성 후 불변).
public record FinanceCategoryCommand(
        UUID parentId,
        FinanceCategory.Type type,
        String name,
        int sortOrder
) {}
