package io.quarkiverse.desktop.awt.it;

import java.awt.EventQueue;
import java.util.concurrent.CountDownLatch;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import io.quarkiverse.desktop.awt.DesktopStartupEvent;

/**
 * Makes the application a user interface application (it observes {@code DesktopStartupEvent}) : the native executable
 * contains the user interface code of the extension (the handlers of {@code java.awt.Desktop}, the exit policy). The
 * event is fired by {@link AwtItMain} ({@code quarkus.desktop.awt.startup-event.mode=manual}) when the JVM has a
 * display.
 */
@Singleton
public class UserInterface {

    final CountDownLatch started = new CountDownLatch(1);

    volatile String thread;

    volatile boolean eventDispatchThread;

    void start(@Observes DesktopStartupEvent event) {
        thread = Thread.currentThread().getName();
        eventDispatchThread = EventQueue.isDispatchThread();
        started.countDown();
    }
}
