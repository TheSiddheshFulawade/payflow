package com.siddheshfulawade.payflow.wallet;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Package rules from SPEC.md section 3. Only production classes are checked: tests may use any test
 * library.
 */
@AnalyzeClasses(packages = ArchitectureTest.BASE, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    static final String BASE = "com.siddheshfulawade.payflow.wallet";

    private static final String DOMAIN = BASE + ".domain..";
    private static final String INFRA = BASE + ".infra..";
    private static final String API = BASE + ".api..";
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
    static final ArchRule domainDoesNotDependOnInfraApiOrApp = noClasses()
            .that()
            .resideInAPackage(DOMAIN)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(INFRA, API, APP);

    @ArchTest
    static final ArchRule apiDoesNotDependOnInfra = noClasses()
            .that()
            .resideInAPackage(API)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(INFRA);

    @ArchTest
    static final ArchRule serviceDoesNotTouchOtherServices = noClasses()
            .that()
            .resideInAPackage(BASE + "..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.siddheshfulawade.payflow.payment..",
                    "com.siddheshfulawade.payflow.risk..",
                    "com.siddheshfulawade.payflow.recon..")
            .because("services talk through messages and HTTP, never through the classes of another service");

    @ArchTest
    static final ArchRule packagesAreFreeOfCycles =
            slices().matching(BASE + ".(*)..").should().beFreeOfCycles();
}
