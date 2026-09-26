package ge.kcamp.linkup.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Belt-and-suspenders on top of Spring Modulith's own verification: {@code feed} and
 * {@code notification} are meant to be leaf/consumer modules that react to events from
 * {@code activity}/{@code social}, never the other way around.
 */
@AnalyzeClasses(packages = "ge.kcamp.linkup", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryArchTests {

    @ArchTest
    static final ArchRule activity_should_not_depend_on_feed_or_notification = noClasses()
            .that().resideInAPackage("..activity..")
            .should().dependOnClassesThat().resideInAnyPackage("..feed..", "..notification..");

    @ArchTest
    static final ArchRule social_should_not_depend_on_feed_or_notification = noClasses()
            .that().resideInAPackage("..social..")
            .should().dependOnClassesThat().resideInAnyPackage("..feed..", "..notification..");
}
