package com.kista.tradingcore.stats.adapter.in.web.dto;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

import java.math.BigDecimal;

// BigDecimal을 지수 표기(5E-7, 1.505E+8) 없이 plain 숫자로 직렬화 — 전역 설정을 건드리지 않고 이 DTO 필드에만 적용
class PlainDecimalSerializer extends StdSerializer<BigDecimal> {

    PlainDecimalSerializer() {
        super(BigDecimal.class);
    }

    @Override
    public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) {
        gen.writeNumber(value.toPlainString());
    }
}
