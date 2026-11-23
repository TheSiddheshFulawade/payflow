package com.siddheshfulawade.payflow.contracts;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The contracts are plain message records shared by every service (SPEC.md section 3). Only
 * production classes are checked.
 */
@AnalyzeClasses(
        packages = "com.siddheshfulawade.payflow.contracts",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule contractsHaveNoSpring =
            noClasses().should().dependOnClassesThat().resideInAPackage("org.springframework..");

    @ArchTest
    static final ArchRule contractsDependOnNoOtherPayflowPackage = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.siddheshfulawade.payflow.messaging..",
                    "com.siddheshfulawade.payflow.payment..",
                    "com.siddheshfulawade.payflow.wallet..",
                    "com.siddheshfulawade.payflow.risk..",
                    "com.siddheshfulawade.payflow.recon..");
}
