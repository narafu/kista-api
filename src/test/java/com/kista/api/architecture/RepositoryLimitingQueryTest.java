package com.kista.api.architecture;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

// 파생 쿼리 subject에 숫자 없는 Top/First + Pageable 금지 — PartTree가 1건으로 고정해 Pageable limit이 무시된다
// (CyclePositionJpaRepository.findTopN…이 리버스모드 별지점을 종가 1개로 계산하게 만든 사례)
class RepositoryLimitingQueryTest {

    // By 앞 subject에서 숫자가 뒤따르지 않는 Top/First (Top1/First5는 의도적 고정 건수라 허용)
    private static final Pattern UNNUMBERED_LIMIT = Pattern.compile("(Top|First)(?!\\d)");

    @Test
    void derivedQueriesWithPageable_mustNotUseUnnumberedTopOrFirst() {
        // 루트 테스트 classpath에 :trading-core main이 있어 두 서브프로젝트 리포지토리를 함께 검사한다
        List<JavaMethod> derivedPageableMethods = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.kista")
                .stream()
                .filter(c -> c.isInterface() && c.isAssignableTo(Repository.class))
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> !m.isAnnotatedWith(Query.class))
                .filter(m -> m.getRawParameterTypes().stream().anyMatch(p -> p.isEquivalentTo(Pageable.class)))
                .toList();

        // 스캔 대상이 비면 규칙이 조용히 통과하므로 trading-core 리포지토리가 잡히는지 함께 확인
        assertThat(derivedPageableMethods)
                .extracting(m -> m.getOwner().getPackageName())
                .anyMatch(p -> p.startsWith("com.kista.tradingcore"));
        assertThat(derivedPageableMethods)
                .filteredOn(m -> UNNUMBERED_LIMIT.matcher(subjectOf(m.getName())).find())
                .extracting(JavaMethod::getFullName)
                .isEmpty();
    }

    // 파생 쿼리 subject — 첫 "By" 앞부분 (findTopNByX → findTopN)
    private static String subjectOf(String methodName) {
        int by = methodName.indexOf("By");
        return by < 0 ? methodName : methodName.substring(0, by);
    }
}
