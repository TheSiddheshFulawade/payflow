package com.siddheshfulawade.payflow.contracts;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The contracts are plain message records shared by every service (SPEC.md section 3). Only
 * production classes are checked. Remove {@code allowEmptyShould} once the module has its first
 * class, so that a rename cannot silently switch the rule off.
 */
@AnalyzeClasses(
        packages = "com.siddheshfulawade.payflow.contracts",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule contractsHaveNoSpring = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule contractsDependOnNoOtherPayflowPackage = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.siddheshfulawade.payflow.messaging..",
                    "com.siddheshfulawade.payflow.payment..",
                    "com.siddheshfulawade.payflow.wallet..",
                    "com.siddheshfulawade.payflow.risk..",
                    "com.siddheshfulawade.payflow.recon..")
            .allowEmptyShould(true);
}
