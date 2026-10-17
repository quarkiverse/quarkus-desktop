package io.quarkiverse.desktop.awt.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.EventQueue;
import java.awt.Toolkit;
import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

import io.quarkiverse.desktop.awt.runtime.DesktopAwtSupport.ApplicationEventQueue;

/**
 * The event queue that dispatches the events with the class loader of the running application (dev and test modes) :
 * removing it never removes a queue pushed after it.
 */
class ApplicationEventQueueTest {

    /**
     * An event queue of the application (a busy cursor queue, an event dispatch thread checker...).
     */
    static final class ApplicationQueue extends EventQueue {
        void remove() {
            pop();
        }
    }

    @Test
    void removesOnlyItself() {
        EventQueue system = top();
        ApplicationEventQueue first = new ApplicationEventQueue(loader());
        system.push(first);
        ApplicationQueue application = new ApplicationQueue();
        system.push(application);

        // the first application stops while its own queue is on top : that queue stays
        first.remove();
        assertSame(application, top());
        assertTrue(first.isRemoved());

        // another application starts and stops
        ApplicationEventQueue second = new ApplicationEventQueue(loader());
        system.push(second);
        assertSame(second, top());
        second.remove();
        assertSame(application, top());

        // the queue of the first application is back on top once the queue above it is removed, and removed by the next
        // removal
        application.remove();
        assertSame(first, top());
        ApplicationEventQueue third = new ApplicationEventQueue(loader());
        system.push(third);
        assertFalse(third.isRemoved());
        third.remove();
        assertSame(system, top());
    }

    @Test
    void removesInReverseOrder() {
        EventQueue system = top();
        ApplicationEventQueue first = new ApplicationEventQueue(loader());
        system.push(first);
        ApplicationEventQueue second = new ApplicationEventQueue(loader());
        system.push(second);

        // not in the order they were pushed (a dev mode application and a test application)
        first.remove();
        assertSame(second, top());
        second.remove();
        assertSame(system, top());
    }

    private static EventQueue top() {
        return Toolkit.getDefaultToolkit().getSystemEventQueue();
    }

    private static ClassLoader loader() {
        return new URLClassLoader(new URL[0], ApplicationEventQueueTest.class.getClassLoader());
    }
}
