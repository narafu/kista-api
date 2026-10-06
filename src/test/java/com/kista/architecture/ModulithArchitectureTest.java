package com.kista.architecture;

import com.kista.KistaApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

@DisplayName("Spring Modulith 모듈 경계 규칙")
class ModulithArchitectureTest {

    @Test
    @DisplayName("모듈 간 의존이 허용된 방향으로만 존재하고 순환이 없다")
    void verifyModularStructure() {
        var modules = ApplicationModules.of(KistaApplication.class).verify();

        // withoutClean — 같은 폴더의 process.html(ProcessMapTest 생성)을 실행 순서에 따라 지우지 않도록
        new Documenter(modules, Documenter.Options.defaults().withoutClean())
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml();
        ModuleGraphExporter.write(modules); // 클릭 탐색용 modules.html
    }
}
