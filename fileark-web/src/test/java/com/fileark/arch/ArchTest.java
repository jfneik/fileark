package com.fileark.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** 架构守护（设计文档 §9）：模块依赖方向与 core 的零框架约束。 */
@AnalyzeClasses(packages = "com.fileark", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchTest {

    @ArchTest
    static final ArchRule coreMustNotDependOnSpring =
            noClasses().that().resideInAPackage("..fileark.core..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("org.springframework..", "jakarta..");

    @ArchTest
    static final ArchRule coreMustNotDependOnOuterModules =
            noClasses().that().resideInAPackage("..fileark.core..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..fileark.storage..", "..fileark.web..");

    @ArchTest
    static final ArchRule storageMustNotDependOnWeb =
            noClasses().that().resideInAPackage("..fileark.storage..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..fileark.web..");
}
