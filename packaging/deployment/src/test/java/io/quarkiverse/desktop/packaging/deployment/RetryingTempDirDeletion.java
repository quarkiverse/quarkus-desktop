package io.quarkiverse.desktop.packaging.deployment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirDeletionStrategy;

/**
 * The deletion of the temporary directories of the tests ({@code @TempDir}), the default one of the module
 * ({@code junit-platform.properties}) : the standard deletion of JUnit, again while it fails. On Windows, an antivirus
 * may read a new file a moment after the test writes it : the file stays in its directory after its deletion, until
 * the antivirus closes it, and the directory is not empty. A file that the code under test leaves open still fails the
 * test, once the deletion stops.
 */
public final class RetryingTempDirDeletion implements TempDirDeletionStrategy {

    /**
     * The time between two deletions.
     */
    private static final Duration INTERVAL = Duration.ofMillis(50);

    /**
     * The deletion that is done again.
     */
    private final TempDirDeletionStrategy delegate;

    /**
     * How long the deletion is done again, from the first one.
     */
    private final Duration timeout;

    /**
     * The standard deletion of JUnit, done again for 10 seconds.
     */
    public RetryingTempDirDeletion() {
        this(TempDirDeletionStrategy.Standard.INSTANCE, Duration.ofSeconds(10));
    }

    /**
     * A deletion done again for a time.
     */
    RetryingTempDirDeletion(TempDirDeletionStrategy delegate, Duration timeout) {
        this.delegate = delegate;
        this.timeout = timeout;
    }

    /**
     * Deletes the directory, again every 50 milliseconds while it fails, until the timeout : a directory that is gone
     * after a failed deletion is deleted (its last file was closed meanwhile).
     *
     * @return the result of the last deletion
     */
    @Override
    public DeletionResult delete(Path directory, AnnotatedElementContext element, ExtensionContext context)
            throws IOException {
        long start = System.nanoTime();
        DeletionResult result = delegate.delete(directory, element, context);
        while (!result.isSuccessful()) {
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                return DeletionResult.builder(directory).build();
            }
            if (System.nanoTime() - start >= timeout.toNanos()) {
                return result;
            }
            try {
                Thread.sleep(INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;
            }
            result = delegate.delete(directory, element, context);
        }
        return result;
    }
}
