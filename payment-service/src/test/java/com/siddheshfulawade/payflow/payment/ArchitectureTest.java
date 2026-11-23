package com.siddheshfulawade.payflow.payment;

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
 *
 * <p>Rules for packages that do not exist yet allow an empty match, so the build stays green while
 * the service grows task by task. When a package gets its first class, remove {@code allowEmptyShould}
 * from its rule so that a rename cannot silently switch the rule off.
 */
@AnalyzeClasses(packages = ArchitectureTest.BASE, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    static final String BASE = "com.siddheshfulawade.payflow.payment";

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
            .allowEmptyShould(true)
            .because("the domain stays plain Java so its rules can be tested without a framework");

    @ArchTest
    static final ArchRule domainHasNoSpring = noClasses()
            .that()
            .resideInAPackage(DOMAIN)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domainDoesNotDependOnInfraApiOrApp = noClasses()
            .that()
            .resideInAPackage(DOMAIN)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(INFRA, API, APP)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule apiDoesNotDependOnInfra = noClasses()
            .that()
            .resideInAPackage(API)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(INFRA)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule serviceDoesNotTouchOtherServices = noClasses()
            .that()
            .resideInAPackage(BASE + "..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.siddheshfulawade.payflow.wallet..",
                    "com.siddheshfulawade.payflow.risk..",
                    "com.siddheshfulawade.payflow.recon..")
            .allowEmptyShould(true)
            .because("services talk through messages and HTTP, never through the classes of another service");

    @ArchTest
    static final ArchRule packagesAreFreeOfCycles =
            slices().matching(BASE + ".(*)..").should().beFreeOfCycles().allowEmptyShould(true);

    // TODO-ME: add a rule `moneyIsNeverFloatingPoint`: no field of type double, float, Double or Float in DOMAIN
    // (CLAUDE.md: money is long minor units). Hint: noFields().that().areDeclaredInClassesThat()
    // .resideInAPackage(DOMAIN).should().haveRawType(double.class) ... (one rule per type, or
    // haveRawType(DescribedPredicate.or(...))). Decide whether it needs allowEmptyShould(true) while the domain is
    // empty.
    // How to check: add `double amount;` to any class in ..domain.. and see the rule fail; remove it and see
    // it pass. Run: ./mvnw -q -pl payment-service test -Dtest=ArchitectureTest
}
