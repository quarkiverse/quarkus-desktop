package io.quarkiverse.desktop.packaging.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
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
}
