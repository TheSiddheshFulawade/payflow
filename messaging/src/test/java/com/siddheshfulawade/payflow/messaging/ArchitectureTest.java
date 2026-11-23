package com.siddheshfulawade.payflow.messaging;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The messaging library is shared infrastructure: it must never know a service (SPEC.md section 3).
 */
@AnalyzeClasses(
        packages = "com.siddheshfulawade.payflow.messaging",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule messagingDoesNotDependOnAnyService = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.siddheshfulawade.payflow.payment..",
                    "com.siddheshfulawade.payflow.wallet..",
                    "com.siddheshfulawade.payflow.risk..",
                    "com.siddheshfulawade.payflow.recon..");
}
