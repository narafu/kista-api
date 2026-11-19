package com.kista.shared.platform.role;

// kista.role 값 — web(API 응답)·worker(배치·매매 엔진)·all(둘 다, 로컬·테스트)
public enum KistaRole {
    WEB, WORKER, ALL;

    public static final String PROPERTY = "kista.role"; // 설정 키

    // 소문자 web|worker|all만 허용(앞뒤 공백 제외) — 누락·오타는 기동 실패(운영 role 오설정 방지)
    public static KistaRole parse(String value) {
        if (value != null) {
            switch (value.trim()) {
                case "web" -> { return WEB; }
                case "worker" -> { return WORKER; }
                case "all" -> { return ALL; }
                default -> { }
            }
        }
        throw new IllegalStateException(PROPERTY + " 설정이 없거나 올바르지 않습니다. web·worker·all 중 하나를 지정해야 합니다.");
    }
}
