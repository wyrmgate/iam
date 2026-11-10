package io.wyrmgate.iam.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "io.wyrmgate.iam", importOptions = ImportOption.DoNotIncludeTests.class)
class PersistenceArchitectureTest {

    @ArchTest
    static final ArchRule frameworkNeutralDomainAndContextBoundaries = noClasses()
            .that()
            .resideInAnyPackage(
                    "..domain..",
                    "..access.application..",
                    "..identity.application..",
                    "..administration.application..",
                    "..platform.id..",
                    "..platform.lifecycle..",
                    "..platform.tenant..",
                    "..tenancy.domain..",
                    "..tenancy.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "java.sql..", "javax.sql..");

    @ArchTest
    static final ArchRule lifecycleKernelDoesNotDependOnBusinessCapabilities = noClasses()
            .that()
            .resideInAPackage("..platform.lifecycle..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "..access..",
                    "..administration..",
                    "..audit..",
                    "..catalog..",
                    "..credential..",
                    "..governance..",
                    "..identity..",
                    "..integration..",
                    "..tenancy..");
}
