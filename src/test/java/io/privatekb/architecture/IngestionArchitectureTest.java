package io.privatekb.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class IngestionArchitectureTest {

    private static final String INTERNAL = "io.privatekb.ingestion.internal.";
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.privatekb");

    @Test
    void domainDoesNotDependOnUseCasesOrAdapters() {
        noClasses().that().resideInAPackage(INTERNAL + "domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        INTERNAL + "application..", INTERNAL + "web..",
                        INTERNAL + "parsing..", INTERNAL + "indexing..",
                        INTERNAL + "persistence..")
                .check(CLASSES);
    }

    @Test
    void useCasesDependOnPortsInsteadOfAdapterImplementations() {
        noClasses().that().resideInAPackage(INTERNAL + "application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        INTERNAL + "web..", INTERNAL + "parsing..",
                        INTERNAL + "indexing..", INTERNAL + "persistence..")
                .check(CLASSES);
    }

    @Test
    void webDoesNotBypassUseCasesToReachAdapters() {
        noClasses().that().resideInAPackage(INTERNAL + "web..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        INTERNAL + "parsing..", INTERNAL + "indexing..",
                        INTERNAL + "persistence..")
                .check(CLASSES);
    }

    @Test
    void jdbcAndParsingLibrariesStayInTheirAdapters() {
        noClasses().that().resideInAnyPackage(
                        INTERNAL + "domain..", INTERNAL + "application..", INTERNAL + "web..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.jdbc..", "java.sql..",
                        "org.apache.tika..", "org.apache.pdfbox..")
                .check(CLASSES);
    }
}
