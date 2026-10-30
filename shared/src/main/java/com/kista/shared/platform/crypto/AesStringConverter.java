package com.kista.shared.platform.crypto;

import jakarta.persistence.AttributeConverter;

// 엔티티 컬럼 AES-256-GCM 암복호화 — 필드마다 @Convert(converter = AesStringConverter.class)로 명시 적용(autoApply 없음).
// 빈 등록 불필요: Spring의 HibernateJpaVendorAdapter가 SpringBeanContainer를 Hibernate에 물려 컨버터를 createBean(생성자 주입)으로 만든다.
// null은 그대로 null — AES-GCM은 비결정론적이라 이 컬럼을 JPQL/파생 쿼리 조건에 쓰면 매칭되지 않는다(조회는 *_hash 컬럼 경유)
public class AesStringConverter implements AttributeConverter<String, String> {

    private final AesCryptoService crypto;

    public AesStringConverter(AesCryptoService crypto) {
        this.crypto = crypto;
    }

    @Override
    public String convertToDatabaseColumn(String plain) {
        return plain == null ? null : crypto.encrypt(plain);
    }

    @Override
    public String convertToEntityAttribute(String cipher) {
        return cipher == null ? null : crypto.decrypt(cipher);
    }
}
