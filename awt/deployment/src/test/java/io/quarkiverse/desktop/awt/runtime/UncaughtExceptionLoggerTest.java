package io.quarkiverse.desktop.awt.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The uncaught exceptions are logged in the category {@code io.quarkiverse.desktop.awt.edt} when the JVM has no default
 * handler, and the previous handler is restored.
 */
class UncaughtExceptionLoggerTest {

    private Thread.UncaughtExceptionHandler previous;

    @BeforeEach
    void saveHandler() {
        previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(null);
    }

    @AfterEach
    void restoreHandler() {
        Thread.setDefaultUncaughtExceptionHandler(previous);
    }

    @Test
    void logsUncaughtExceptions() throws Exception {
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(UncaughtExceptionLogger.CATEGORY);
        logger.addHandler(handler);
        UncaughtExceptionLogger installed = UncaughtExceptionLogger.install();
        try {
            assertNotNull(installed);
            assertSame(installed, Thread.getDefaultUncaughtExceptionHandler());
            IllegalStateException failure = new IllegalStateException("listener failure");
            Thread thread = new Thread(() -> {
                throw failure;
            }, "failing-thread");
            thread.start();
            thread.join();
            assertEquals(1, records.size(), records.toString());
            LogRecord record = records.get(0);
            // ERROR
            assertEquals(Level.SEVERE.intValue(), record.getLevel().intValue());
            assertSame(failure, record.getThrown());
            String message = record.getParameters() == null ? record.getMessage()
                    : String.format(record.getMessage(), record.getParameters());
            assertEquals("Uncaught exception in the thread failing-thread", message);
        } finally {
            logger.removeHandler(handler);
            if (installed != null) {
                installed.remove();
            }
        }
        assertNull(Thread.getDefaultUncaughtExceptionHandler());
    }

    @Test
    void keepsExistingHandler() {
        Thread.UncaughtExceptionHandler application = (thread, e) -> {
        };
        Thread.setDefaultUncaughtExceptionHandler(application);
        assertNull(UncaughtExceptionLogger.install());
        assertSame(application, Thread.getDefaultUncaughtExceptionHandler());
    }

    @Test
    void removeKeepsReplacingHandler() {
        UncaughtExceptionLogger installed = UncaughtExceptionLogger.install();
        assertNotNull(installed);
        // the application replaced it
        Thread.UncaughtExceptionHandler application = (thread, e) -> {
        };
        Thread.setDefaultUncaughtExceptionHandler(application);
        installed.remove();
        assertSame(application, Thread.getDefaultUncaughtExceptionHandler());
    }
}
