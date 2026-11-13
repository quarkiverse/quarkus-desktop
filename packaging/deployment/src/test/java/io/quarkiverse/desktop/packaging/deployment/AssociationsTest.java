package io.quarkiverse.desktop.packaging.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.desktop.packaging.spi.OpenedTypesBuildItem;

/**
 * The file types and URL schemes of the configuration, which the packages register the application for.
 */
class AssociationsTest {

    @Test
    void noFileTypeNoScheme() {
        AppInfo app = TestConfig.app(TestConfig.defaults());
        assertTrue(app.associations().isEmpty());
        assertEquals(Associations.NONE, app.associations());
    }

    @Test
    void theFileTypesAndSchemesWithTheirDefaults() {
        DesktopPackagingConfig config = TestConfig.of(Map.of("file-types.document.extensions", ".ACME, acmex",
                "file-types.template.extensions", "acmt", "file-types.template.mime-type", "Application/Vnd.Acme.Template",
                "file-types.template.description", "  Acme template ", "url-schemes", "Acme, acme-editor://"));
        Associations associations = TestConfig.app(config).associations();
        assertEquals(List.of("document", "template"), associations.fileTypes().stream().map(Associations.FileType::name)
                .toList());
        Associations.FileType document = associations.fileTypes().get(0);
        assertEquals(List.of("acme", "acmex"), document.extensions());
        assertEquals("application/x-acme-editor-document", document.mimeType());
        assertEquals("Acme Editor document", document.description());
        assertEquals(Optional.empty(), document.icon());
        assertEquals("org.acme.AcmeEditor.document", document.identifier(TestConfig.app(config)));
        Associations.FileType template = associations.fileTypes().get(1);
        assertEquals("application/vnd.acme.template", template.mimeType());
        assertEquals("Acme template", template.description());
        assertEquals(List.of("acme", "acme-editor"), associations.urlSchemes());
        assertEquals(Set.of("acme", "acmex", "acmt"), associations.extensions());
    }

    @Test
    void theIconOfAFileType(@TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve("src/main/packaging"));
        Files.write(project.resolve("src/main/packaging/document.png"), new byte[] { 1 });
        DesktopPackagingConfig config = TestConfig.of(Map.of("file-types.document.extensions", "acme",
                "file-types.document.icon", "src/main/packaging/document.png"));
        Associations associations = AppInfo.of(config, "Acme Editor", "1.0", "org.acme", project).associations();
        assertEquals(Optional.of(project.resolve("src/main/packaging/document.png")),
                associations.fileTypes().get(0).icon());

        DesktopPackagingConfig missing = TestConfig.of(Map.of("file-types.document.extensions", "acme",
                "file-types.document.icon", "missing.png"));
        PackagingException e = assertThrows(PackagingException.class,
                () -> AppInfo.of(missing, "Acme Editor", "1.0", "org.acme", project));
        assertTrue(e.getMessage().startsWith("quarkus.desktop.packaging.file-types.document.icon : "), e.getMessage());
    }

    @Test
    void theValuesThatTheFormatsReject() {
        for (Map<String, String> properties : List.of(
                Map.of("file-types.Document.extensions", "acme"),
                Map.of("file-types.document.extensions", "a b"),
                Map.of("file-types.document.extensions", "acme", "file-types.other.extensions", "ACME"),
                Map.of("file-types.document.extensions", "acme", "file-types.document.mime-type", "acme"),
                Map.of("url-schemes", "1acme"),
                Map.of("url-schemes", "acme editor"),
                Map.of("url-schemes", "https"))) {
            PackagingException e = assertThrows(PackagingException.class,
                    () -> TestConfig.app(TestConfig.of(properties)), properties.toString());
            assertTrue(e.getMessage().startsWith("quarkus.desktop.packaging."), e.getMessage());
        }
    }

    /**
     * The extensions, the schemes and the identifier that the user interface toolkits get, without the checks of the
     * packages.
     */
    @Test
    void theOpenedTypesOfTheToolkits() {
        OpenedTypesBuildItem types = DesktopPackagingProcessor.openedTypes(TestConfig.of(Map.of(
                "file-types.document.extensions", ".ACME,acmex", "url-schemes", "Acme://")), "acme-editor", "org.acme");
        assertEquals(Set.of("acme", "acmex"), types.getExtensions());
        assertEquals(Set.of("acme"), types.getSchemes());
        assertEquals(AppInfo.identifier("org.acme", "acme-editor"), types.getIdentifier());

        OpenedTypesBuildItem configured = DesktopPackagingProcessor.openedTypes(TestConfig.of(Map.of(
                "identifier", " org.acme.Editor ", "name", "Acme Editor")), "acme-editor", "org.acme");
        assertEquals(Set.of(), configured.getExtensions());
        assertEquals("org.acme.Editor", configured.getIdentifier());
        assertEquals(TestConfig.app(TestConfig.of(Map.of("name", "Acme Editor"))).identifier(),
                DesktopPackagingProcessor.openedTypes(TestConfig.of(Map.of("name", "Acme Editor")), "x", "org.acme")
                        .getIdentifier());
    }
}
