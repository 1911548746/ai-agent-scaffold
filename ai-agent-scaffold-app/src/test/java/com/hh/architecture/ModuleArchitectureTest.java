package com.hh.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 固化当前多模块工程的依赖方向。
 *
 * <p>该测试只分析已编译字节码，不创建 Spring Context，也不会建立模型、MCP 或数据库连接。</p>
 */
class ModuleArchitectureTest {

    private static final JavaClasses PROJECT_CLASSES = new ClassFileImporter().importPackages("com.hh");

    @Test
    void domainMustNotDependOnOuterLayersOrFrameworkImplementations() {
        noClasses()
                .that().resideInAPackage("com.hh.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.hh.application..",
                        "com.hh.infrastructure..",
                        "com.hh.trigger..",
                        "com.hh.api..",
                        "com.hh.config..",
                        "org.springframework..",
                        "com.baomidou..",
                        "com.google.adk..",
                        "dev.langchain4j..")
                .check(PROJECT_CLASSES);
    }

    @Test
    void applicationMustNotDependOnDeliveryInfrastructureOrSpringAssembly() {
        noClasses()
                .that().resideInAPackage("com.hh.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.hh.infrastructure..",
                        "com.hh.trigger..",
                        "com.hh.config..",
                        "org.springframework..")
                .check(PROJECT_CLASSES);
    }

    @Test
    void apiContractMustNotExposeFrameworkOrAgentSdkTypes() {
        noClasses()
                .that().resideInAPackage("com.hh.api..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "com.baomidou..",
                        "com.google.adk..",
                        "dev.langchain4j..",
                        "io.reactivex..")
                .check(PROJECT_CLASSES);
    }
}
