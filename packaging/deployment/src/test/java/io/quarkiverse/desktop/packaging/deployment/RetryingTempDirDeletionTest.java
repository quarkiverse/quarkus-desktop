package io.quarkiverse.desktop.packaging.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirDeletionStrategy;
import org.junit.jupiter.api.io.TempDirDeletionStrategy.DeletionFailure;
import org.junit.jupiter.api.io.TempDirDeletionStrategy.DeletionResult;

import com.sun.nio.file.ExtendedOpenOption;

/**
 * The deletion of the temporary directories of the tests, again while it fails.
 */
class RetryingTempDirDeletionTest {

    /**
     * A deletion that fails the given number of times, then deletes the directory.
     *
     * @param calls counts the deletions
     */
    static TempDirDeletionStrategy failing(int failures, AtomicInteger calls) {
        return (directory, element, context) -> {
            DeletionResult.Builder result = DeletionResult.builder(directory);
            if (calls.incrementAndGet() <= failures) {
                result.addFailure(directory, new DirectoryNotEmptyException(directory.toString()));
            } else {
                Files.delete(directory);
            }
            return result.build();
        };
    }

    /**
     * A deletion that fails a moment is done again until it succeeds, and one that succeeds is done once.
     */
    @Test
    void aDeletionThatFailsAMomentIsDoneAgain(@TempDir Path parent) throws IOException {
        Path directory = Files.createDirectory(parent.resolve("failing"));
        AtomicInteger calls = new AtomicInteger();
        assertTrue(new RetryingTempDirDeletion(failing(2, calls), Duration.ofSeconds(10)).delete(directory, null, null)
                .isSuccessful());
        assertEquals(3, calls.get());
        assertFalse(Files.exists(directory));

        Files.createDirectory(directory);
        calls.set(0);
        assertTrue(new RetryingTempDirDeletion(failing(0, calls), Duration.ofSeconds(10)).delete(directory, null, null)
                .isSuccessful());
        assertEquals(1, calls.get());
    }

    /**
     * A deletion that keeps failing fails, with its failures, once the timeout is over.
     */
    @Test
    void aDeletionThatKeepsFailingFails(@TempDir Path directory) throws IOException {
        AtomicInteger calls = new AtomicInteger();
        DeletionResult result = new RetryingTempDirDeletion(failing(Integer.MAX_VALUE, calls), Duration.ofMillis(300))
                .delete(directory, null, null);
        assertFalse(result.isSuccessful());
        assertEquals(List.of(directory), result.failures().stream().map(DeletionFailure::path).toList());
        assertTrue(calls.get() > 1, calls.toString());
        assertTrue(Files.isDirectory(directory));
    }

    /**
     * A directory that is gone after a failed deletion is deleted : its last file was closed meanwhile.
     */
    @Test
    void aDirectoryThatIsGoneIsDeleted(@TempDir Path parent) throws IOException {
        Path directory = Files.createDirectory(parent.resolve("gone"));
        AtomicInteger calls = new AtomicInteger();
        TempDirDeletionStrategy deletion = (path, element, context) -> {
            calls.incrementAndGet();
            Files.delete(path);
            return DeletionResult.builder(path).addFailure(path.resolve("acme.dll"),
                    new DirectoryNotEmptyException(path.toString())).build();
        };
        assertTrue(new RetryingTempDirDeletion(deletion, Duration.ofSeconds(10)).delete(directory, null, null)
                .isSuccessful());
        assertEquals(1, calls.get());
    }

    /**
     * A file that another handle holds a moment, without sharing its deletion, as an antivirus does : the standard
     * deletion of JUnit fails, this one deletes the directory once the file is closed.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aFileHeldAMomentIsDeleted(@TempDir Path parent) throws IOException {
        Path directory = Files.createDirectory(parent.resolve("held"));
        Path file = Files.writeString(directory.resolve("acme.dll"), "acme");
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE);
        CompletableFuture<Void> release = null;
        try {
            assertFalse(TempDirDeletionStrategy.Standard.INSTANCE.delete(directory, null, null).isSuccessful());
            assertTrue(Files.exists(file));
            release = CompletableFuture.runAsync(() -> close(channel),
                    CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS));
            assertTrue(new RetryingTempDirDeletion().delete(directory, null, null).isSuccessful());
            assertFalse(Files.exists(directory));
        } finally {
            if (release == null) {
                channel.close();
            } else {
                release.join();
            }
        }
    }

    /**
     * Closes the file that the test holds, from another thread.
     */
    private static void close(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The deletion is the default one of the tests of the module, in the configuration of JUnit.
     */
    @Test
    void theDeletionOfTheTestsOfTheModule() throws IOException {
        Properties configuration = new Properties();
        try (InputStream in = RetryingTempDirDeletionTest.class.getResourceAsStream("/junit-platform.properties")) {
            assertNotNull(in, "junit-platform.properties");
            configuration.load(in);
        }
        assertEquals(RetryingTempDirDeletion.class.getName(),
                configuration.getProperty(TempDir.DEFAULT_DELETION_STRATEGY_PROPERTY_NAME));
    }
}
