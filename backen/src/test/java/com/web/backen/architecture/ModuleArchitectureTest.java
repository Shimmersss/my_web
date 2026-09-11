package com.web.backen.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

class ModuleArchitectureTest {
    private static final String[] FOUNDATIONS = {
            "com.web.backen.auth..", "com.web.backen.config..", "com.web.backen.ai..",
            "com.web.backen.settings..", "com.web.backen.runtime.."
    };
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.web.backen");

    @Test
    void topLevelModulesAreAcyclic() {
        slices().matching("com.web.backen.(*)..")
                .should().beFreeOfCycles().check(PRODUCTION);
    }

    @Test
    void foundationModulesDoNotDependOnFeaturesOrAggregation() {
        noClasses().that().resideInAnyPackage(FOUNDATIONS)
                .should().dependOnClassesThat(resideInAPackage("com.web.backen..")
                        .and(not(resideInAnyPackage(FOUNDATIONS))))
                .check(PRODUCTION);
    }

    @Test
    void productionCodeDoesNotCallHttpControllers() {
        noClasses().should().dependOnClassesThat().areAnnotatedWith(RestController.class)
                .check(PRODUCTION);
    }
}
