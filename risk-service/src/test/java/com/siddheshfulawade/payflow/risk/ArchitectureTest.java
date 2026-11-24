package com.siddheshfulawade.payflow.risk;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Package rules from SPEC.md section 3. Only production classes are checked: tests may use any test library.
 * risk-service has no {@code api} package (only the health endpoint), so there is no rule for it.
 */
@AnalyzeClasses(packages = ArchitectureTest.BASE, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    static final String BASE = "com.siddheshfulawade.payflow.risk";

    private static final String DOMAIN = BASE + ".domain..";
    private static final String INFRA = BASE + ".infra..";
    private static final String APP = BASE + ".app..";
    private static final String CONTRACTS = "com.siddheshfulawade.payflow.contracts..";

    @ArchTest
    static final ArchRule domainDependsOnlyOnJavaItselfAndContracts = classes()
            .that()
            .resideInAPackage(DOMAIN)
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", DOMAIN, CONTRACTS)
            .because("the domain stays plain Java so its rules can be tested without a framework");

    @ArchTest
    static final ArchRule domainHasNoSpring = noClasses()
            .that()
            .resideInAPackage(DOMAIN)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework..");

    @ArchTest
    static final ArchRule domainDoesNotDependOnInfraOrApp = noClasses()
            .that()
            .resideInAPackage(DOMAIN)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(INFRA, APP);

    @ArchTest
    static final ArchRule appDoesNotDependOnInfra = noClasses()
            .that()
            .resideInAPackage(APP)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(INFRA)
            .because("the handler talks to Hazelcast and the database only through its ports");

    @ArchTest
    static final ArchRule hazelcastStaysInInfra = noClasses()
            .that()
            .resideInAnyPackage(DOMAIN, APP)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.hazelcast..")
            .because("the rules must not depend on the cache that implements the velocity count");

    @ArchTest
    static final ArchRule serviceDoesNotTouchOtherServices = noClasses()
            .that()
            .resideInAPackage(BASE + "..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.siddheshfulawade.payflow.payment..",
                    "com.siddheshfulawade.payflow.wallet..",
                    "com.siddheshfulawade.payflow.recon..")
            .because("services talk through messages and HTTP, never through the classes of another service");

    @ArchTest
    static final ArchRule packagesAreFreeOfCycles =
            slices().matching(BASE + ".(*)..").should().beFreeOfCycles();
}
