package com.kista.shared.contract.notify;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

import java.math.BigDecimal;

// BigDecimal을 지수 표기(1.2E-7, 1E+2) 없이 plain 숫자로 직렬화 — 전역 설정 없이 이 계약 필드에만 적용
class PlainDecimalSerializer extends StdSerializer<BigDecimal> {

    PlainDecimalSerializer() {
        super(BigDecimal.class);
    }

    @Override
    public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) {
        gen.writeNumber(value.toPlainString());
    }
}
