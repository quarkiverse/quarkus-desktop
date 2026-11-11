package io.quarkiverse.desktop.packaging.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ToolsTest {

    /**
     * The output of the tools is UTF-8, or in the ANSI code page on Windows.
     */
    @Test
    void theOutputOfTheTools() {
        Charset windows1252 = Charset.forName("windows-1252");
        assertEquals("t\u00e4rget", Tools.decode("t\u00e4rget".getBytes(StandardCharsets.UTF_8), windows1252));
        // the bytes that makeappx writes : one per character
        assertEquals("t\u00e4rget", Tools.decode(new byte[] { 't', (byte) 0xe4, 'r', 'g', 'e', 't' }, windows1252));
        assertEquals("exit code 1", Tools.decode("exit code 1".getBytes(StandardCharsets.US_ASCII), windows1252));
        // a double byte code page : jpackage on a Japanese Windows
        Charset shiftJis = Charset.forName("Shift_JIS");
        assertEquals("\u30a8\u30e9\u30fc", Tools.decode("\u30a8\u30e9\u30fc".getBytes(shiftJis), shiftJis));
    }

    @Test
    void theQuotesOfAWindowsArgument() {
        assertEquals("Edits \\\"big data\\\" files", Tools.windowsArgument("Edits \"big data\" files"));
        assertEquals("ab\\\"cd", Tools.windowsArgument("ab\"cd"));
        // the backslashes before a quote are doubled
        assertEquals("a\\\\\\\"b", Tools.windowsArgument("a\\\"b"));
        assertEquals("\\\"x y\\\"", Tools.windowsArgument("\"x y\""));
        assertEquals("C:\\dir with space\\", Tools.windowsArgument("C:\\dir with space\\"));
        assertEquals("", Tools.windowsArgument(""));
    }

    /**
     * The arguments reach the tool as they are, whatever their quotes and spaces.
     */
    @Test
    void theArgumentsOfATool(@TempDir Path directory) throws IOException {
        Path arguments = Files.writeString(directory.resolve("Arguments.java"), """
                public class Arguments {
                    public static void main(String[] args) {
                        for (String arg : args) {
                            System.out.print("[" + arg + "]");
                        }
                    }
                }
                """);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> values = List.of("Edits \"big data\" files", "ab\"cd", "\"x y\"", "a\\\"b", "C:\\dir with space\\",
                "next");
        List<String> command = new ArrayList<>(List.of(java, arguments.toString()));
        command.addAll(values);
        Tools.Result result = Tools.exec(directory, Map.of(), command);
        assertTrue(result.output().contains("[" + String.join("][", values) + "]"), result.output());
    }

    /**
     * A tool of the configuration is relative to the directory of the project, not to the working directory of the
     * build (a module of a multi-module build), and absolute : the tools run in work directories.
     */
    @Test
    void theToolsOfTheConfiguration(@TempDir Path project) throws IOException {
        Path tool = Files.createDirectories(project.resolve("tools")).resolve("appimagetool-x86_64.AppImage");
        Files.writeString(tool, "tool");
        Tools tools = new Tools(Map.of("appimagetool", "tools/appimagetool-x86_64.AppImage ", "acme-absolute",
                tool.toString(), "acme-blank", " ", "acme-missing", "tools/acme-missing"), project);
        Optional<Path> found = tools.find("appimagetool");
        assertEquals(Optional.of(tool.toAbsolutePath()), found);
        assertTrue(found.get().isAbsolute());
        assertEquals(Optional.of(tool), tools.find("acme-absolute"));
        // a blank path : the PATH, then the locations
        assertEquals(Optional.of(tool), tools.find("acme-blank", project.resolve("missing"), tool));
        PackagingException e = assertThrows(PackagingException.class, () -> tools.find("acme-missing"));
        assertTrue(e.getMessage().startsWith("quarkus.desktop.packaging.tools.acme-missing : "
                + project.resolve("tools").resolve("acme-missing").toAbsolutePath() + " not found"), e.getMessage());
    }

    /**
     * A tool of a relative directory of the {@code PATH} is absolute : the tools run in work directories.
     */
    @Test
    void theToolsOfThePath() throws IOException {
        // relative to the working directory of the tests, the directory of the module
        Path directory = Files.createDirectories(Path.of("target", "tools-test-" + UUID.randomUUID()));
        try {
            boolean windows = TargetOs.host() == TargetOs.WINDOWS;
            Path tool = Files.writeString(directory.resolve(windows ? "acme-tool.exe" : "acme-tool"), "tool");
            FileTrees.executable(tool);
            Optional<Path> found = Tools.onPath("acme-tool", "missing" + File.pathSeparator + directory);
            assertEquals(Optional.of(tool.toAbsolutePath()), found);
            assertTrue(found.get().isAbsolute());
            assertEquals(Optional.empty(), Tools.onPath("acme-tool", null));
        } finally {
            FileTrees.delete(directory);
        }
    }

    @Test
    void theBatchFiles() {
        assertTrue(Tools.isBatchFile("C:\\ci\\sign.cmd"));
        assertTrue(Tools.isBatchFile("C:\\ci\\SIGN.BAT"));
        assertTrue(Tools.isBatchFile("C:\\ci\\sign.cmd. "));
        assertFalse(Tools.isBatchFile("C:\\Program Files (x86)\\Windows Kits\\10\\bin\\x64\\signtool.exe"));
        assertFalse(Tools.isBatchFile("C:\\cmd\\wix"));
    }

    /**
     * The arguments of a batch file are quoted for {@code cmd.exe}, which would run the end of an argument with
     * {@code &} as a command, and the backslashes at their end are doubled for the program that it runs.
     */
    @Test
    void theArgumentsOfABatchFile() {
        assertEquals(List.of("\"C:\\R&D\\sign.cmd\"", "\"sign\"", "\"/p\"", "\"S3cr&x>y\"", "\"C:\\out\\\\\"",
                "\"a|b^c<d\"", "\"x y\"", "\"\""),
                Tools.batchCommand(List.of("C:\\R&D\\sign.cmd", "sign", "/p", "S3cr&x>y", "C:\\out\\", "a|b^c<d", "x y",
                        "")));
        // what cmd.exe changes whatever the quotes
        for (String argument : List.of("100%PATH%", "S3cr\"t", "two\nlines", "two\rlines")) {
            PackagingException e = assertThrows(PackagingException.class,
                    () -> Tools.batchCommand(List.of("C:\\ci\\sign.cmd", "/p", argument)));
            assertTrue(e.getMessage().startsWith("C:\\ci\\sign.cmd is a batch file, which cmd.exe runs : its argument 2"
                    + " has "), e.getMessage());
            assertTrue(e.getMessage().contains("quarkus.desktop.packaging.tools."), e.getMessage());
            // a password is not in the message
            assertFalse(e.getMessage().contains(argument), e.getMessage());
        }
        PackagingException e = assertThrows(PackagingException.class,
                () -> Tools.batchCommand(List.of("C:\\100%\\sign.cmd", "/p")));
        assertTrue(e.getMessage().contains("its path has"), e.getMessage());
    }

    /**
     * A batch file of the configuration gets its arguments as they are, and runs nothing else.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theArgumentsReachTheProgramThatABatchFileRuns(@TempDir Path directory) throws IOException {
        Path arguments = Files.writeString(directory.resolve("Arguments.java"), """
                public class Arguments {
                    public static void main(String[] args) {
                        for (String arg : args) {
                            System.out.print("[" + arg + "]");
                        }
                    }
                }
                """);
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        Assumptions.assumeTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(java + arguments),
                "cmd.exe reads the batch file in the OEM code page");
        // a batch file in a directory with & in its name
        Path batch = Files.createDirectories(directory.resolve("R&D")).resolve("tool.cmd");
        Files.writeString(batch, "@\"" + java + "\" \"" + arguments + "\" %*\r\n", StandardCharsets.US_ASCII);
        List<String> values = List.of("S3cr&echo.>created.txt", "C:\\R&D\\out.msi", "a|b", "a^b", "a<b", "C:\\out\\",
                "https://ts.example/?a=1&b=2", "x y", "");
        List<String> command = new ArrayList<>(List.of(batch.toString()));
        command.addAll(values);
        Tools.Result result = Tools.exec(directory, Map.of(), command);
        assertTrue(result.output().contains("[" + String.join("][", values) + "]"), result.output());
        assertFalse(Files.exists(directory.resolve("created.txt")));
    }
}
