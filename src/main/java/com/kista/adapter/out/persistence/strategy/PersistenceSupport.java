package com.kista.adapter.out.persistence.strategy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.function.Supplier;

// strategy 패키지 내 upsert 공용 헬퍼 — 패키지 내부 전용
final class PersistenceSupport {

    private PersistenceSupport() {}

    // id가 null이면 새 엔티티 생성, non-null이면 DB에서 조회 후 없으면 새로 생성 (find-or-create)
    static <T, ID> T findOrCreate(ID id, JpaRepository<T, ID> repo, Supplier<T> creator) {
        return id != null ? repo.findById(id).orElseGet(creator) : creator.get();
    }
}
