package com.kista.domain.model;

public class InvalidKisKeyException extends RuntimeException {
    public InvalidKisKeyException() {
        super("KIS API 키가 유효하지 않습니다");
    }
}
