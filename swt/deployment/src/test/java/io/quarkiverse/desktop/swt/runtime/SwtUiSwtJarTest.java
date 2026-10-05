package io.quarkiverse.desktop.swt.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.jar.Attributes;

import org.junit.jupiter.api.Test;

/**
 * The SWT jar checked before the {@code Display} is created, in JVM mode : SWT exits the JVM with its own message only
 * when the {@code SWT-OS} and {@code SWT-Arch} attributes of the manifest of its jar are not the operating system and
 * the architecture of the JVM, the extension logs its hint and stops the application instead.
 */
class SwtUiSwtJarTest {

    @Test
    void jarOfThisPlatform() {
        assertTrue(SwtUi.isLoadable(attributes("cocoa", "macosx", "aarch64"), "Mac OS X", "aarch64"));
        assertTrue(SwtUi.isLoadable(attributes("cocoa", "macosx", "x86_64"), "Mac OS X", "x86_64"));
        assertTrue(SwtUi.isLoadable(attributes("win32", "win32", "x86_64"), "Windows 11", "amd64"));
        assertTrue(SwtUi.isLoadable(attributes("win32", "win32", "aarch64"), "Windows 11", "aarch64"));
        assertTrue(SwtUi.isLoadable(attributes("gtk", "linux", "x86_64"), "Linux", "amd64"));
        assertTrue(SwtUi.isLoadable(attributes("gtk", "linux", "aarch64"), "Linux", "aarch64"));
        // SWT does not compare the windowing system
        assertTrue(SwtUi.isLoadable(attributes(null, "linux", "aarch64"), "Linux", "aarch64"));
    }

    @Test
    void jarOfAnotherPlatform() {
        // the x86_64 jar on Apple silicon, and the reverse
        assertFalse(SwtUi.isLoadable(attributes("cocoa", "macosx", "x86_64"), "Mac OS X", "aarch64"));
        assertFalse(SwtUi.isLoadable(attributes("cocoa", "macosx", "aarch64"), "Mac OS X", "x86_64"));
        // another operating system
        assertFalse(SwtUi.isLoadable(attributes("gtk", "linux", "aarch64"), "Mac OS X", "aarch64"));
        assertFalse(SwtUi.isLoadable(attributes("win32", "win32", "x86_64"), "Linux", "amd64"));
        // a manifest without the attributes : SWT exits too
        assertFalse(SwtUi.isLoadable(new Attributes(), "Linux", "amd64"));
    }

    /**
     * The SWT jar of the test class path is the one of the build host, which Maven adds to
     * {@code org.eclipse.platform:org.eclipse.swt}.
     */
    @Test
    void theSwtJarOfTheTests() {
        String osName = System.getProperty("os.name");
        String osArch = System.getProperty("os.arch");
        assertDoesNotThrow(() -> SwtUi.checkSwtJar(osName, osArch));

        // as on a JVM of another architecture
        String otherArch = SwtUi.swtArch(osArch).equals("x86_64") ? "aarch64" : "x86_64";
        UnsatisfiedLinkError e = assertThrows(UnsatisfiedLinkError.class, () -> SwtUi.checkSwtJar(osName, otherArch));
        assertTrue(e.getMessage().startsWith(SwtUi.SWT_JAR_ERROR), e.getMessage());
        assertTrue(e.getMessage().contains("SWT-Arch " + SwtUi.swtArch(osArch) + ", not " + SwtUi.swtOs(osName)
                + " and " + otherArch), e.getMessage());
        String hint = SwtUi.hint(e, osName, false, null);
        assertTrue(hint.contains("SWT could not load its native libraries"), hint);
        assertTrue(hint.contains(SwtUi.swtJar(osName, osArch)), hint);
        // the jar is the cause, not the native libraries that SWT extracted before
        assertFalse(hint.contains("~/.swt/lib"), hint);
    }

    /**
     * An uber-jar keeps its own manifest only : the SWT classes come from a jar without the attributes of SWT, and SWT
     * exits the JVM.
     */
    @Test
    void manifestWithoutTheAttributesOfSwt() throws MalformedURLException {
        URL jar = URI.create("file:/app/target/app-1.0-runner.jar").toURL();
        Attributes uberJar = new Attributes();
        uberJar.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        uberJar.put(Attributes.Name.MAIN_CLASS, "io.quarkus.bootstrap.runner.QuarkusEntryPoint");
        UnsatisfiedLinkError e = assertThrows(UnsatisfiedLinkError.class,
                () -> SwtUi.checkSwtJar(jar, uberJar, "Linux", "amd64"));
        assertEquals("The SWT jar file:/app/target/app-1.0-runner.jar has no SWT-OS and SWT-Arch attributes in its"
                + " manifest : SWT would exit the JVM (System.exit(1)) without them (an uber-jar drops the manifests of"
                + " its dependencies : use the default fast-jar packaging)", e.getMessage());
        assertFalse(SwtUi.hint(e, "Linux", false, null).contains("~/.swt/lib"));

        // a single attribute missing
        Attributes withoutArch = attributes("gtk", "linux", "x86_64");
        withoutArch.remove(new Attributes.Name(SwtUi.SWT_ARCH));
        e = assertThrows(UnsatisfiedLinkError.class, () -> SwtUi.checkSwtJar(jar, withoutArch, "Linux", "amd64"));
        assertTrue(e.getMessage().contains(" has no SWT-Arch attribute in its manifest : SWT would exit the JVM"
                + " (System.exit(1)) without it"),
                e.getMessage());
        Attributes withoutOs = attributes("gtk", "linux", "x86_64");
        withoutOs.remove(new Attributes.Name(SwtUi.SWT_OS));
        e = assertThrows(UnsatisfiedLinkError.class, () -> SwtUi.checkSwtJar(jar, withoutOs, "Linux", "amd64"));
        assertTrue(e.getMessage().contains(" has no SWT-OS attribute in its manifest"), e.getMessage());

        // the attributes of another platform, and of this one
        e = assertThrows(UnsatisfiedLinkError.class,
                () -> SwtUi.checkSwtJar(jar, attributes("gtk", "linux", "aarch64"), "Linux", "amd64"));
        assertTrue(e.getMessage().startsWith("The SWT jar file:/app/target/app-1.0-runner.jar declares, in its"
                + " manifest, SWT-OS linux and SWT-Arch aarch64, not linux and x86_64 : "), e.getMessage());
        assertDoesNotThrow(() -> SwtUi.checkSwtJar(jar, attributes("gtk", "linux", "x86_64"), "Linux", "amd64"));
    }

    @Test
    void jarToUse() {
        assertEquals("org.eclipse.platform:org.eclipse.swt.cocoa.macosx.aarch64", SwtUi.swtJar("Mac OS X", "aarch64"));
        assertEquals("org.eclipse.platform:org.eclipse.swt.cocoa.macosx.x86_64", SwtUi.swtJar("Mac OS X", "x86_64"));
        assertEquals("org.eclipse.platform:org.eclipse.swt.win32.win32.x86_64", SwtUi.swtJar("Windows 11", "amd64"));
        assertEquals("org.eclipse.platform:org.eclipse.swt.gtk.linux.x86_64", SwtUi.swtJar("Linux", "amd64"));
        assertEquals("org.eclipse.platform:org.eclipse.swt.gtk.linux.aarch64", SwtUi.swtJar("Linux", "aarch64"));
        // a platform without SWT jar
        assertEquals("org.eclipse.platform:org.eclipse.swt.<ws>.<os>.<arch>", SwtUi.swtJar("FreeBSD", "amd64"));
    }

    private static Attributes attributes(String ws, String os, String arch) {
        Attributes attributes = new Attributes();
        if (ws != null) {
            attributes.putValue("SWT-WS", ws);
        }
        attributes.putValue(SwtUi.SWT_OS, os);
        attributes.putValue(SwtUi.SWT_ARCH, arch);
        return attributes;
    }
}
