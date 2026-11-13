package io.quarkiverse.desktop.swt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.packaging.spi.OpenedTypesBuildItem;
import io.quarkiverse.desktop.swt.OpenRequest;
import io.quarkiverse.desktop.swt.SwtStartupEvent;
import io.quarkiverse.desktop.swt.UiThread;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * {@code OpenRequest} is fired on the user interface thread after {@code SwtStartupEvent}, with the program arguments
 * of the file types and URL schemes of the packages ({@code OpenedTypesBuildItem}, here produced by a build step of the
 * test, as the Desktop Packaging extension does), then the {@code SWT.OpenDocument} events of the {@code Display}
 * (macOS), also those that came before. The other arguments are left to the application. Needs a display. Not on macOS,
 * where the {@code Display} needs the first thread of the process.
 */
@DisabledOnOs(OS.MAC)
class OpenRequestTest {

    static final Path FILE = createFile("Notes.ACME");

    static final Path DOCUMENT = createFile("document.acme");

    @Singleton
    public static class Observer {

        static final List<String> EVENTS = new CopyOnWriteArrayList<>();
        static final List<OpenRequest> REQUESTS = new CopyOnWriteArrayList<>();
        static final CountDownLatch OPENED = new CountDownLatch(3);
        static volatile boolean onUiThread = true;

        void onStart(@Observes SwtStartupEvent event) {
            EVENTS.add("SwtStartupEvent");
            // as macOS does when the application starts with a document : collected, fired after the launch request
            send(event.display(), SWT.OpenDocument, DOCUMENT.toString());
        }

        void open(@Observes OpenRequest request) {
            onUiThread &= UiThread.isUiThread();
            EVENTS.add("OpenRequest");
            REQUESTS.add(request);
            OPENED.countDown();
            if (REQUESTS.size() == 2) {
                // while the user interface runs : fired at once
                send(Display.getCurrent(), SWT.OpenUrl, "acme://later");
            }
        }
    }

    /**
     * Sends an event to the listeners of the {@code Display}, as SWT does for the Apple Events of macOS
     * ({@code Display.sendEvent}, package private).
     */
    static void send(Display display, int type, String text) {
        Event event = new Event();
        event.text = text;
        try {
            Method sendEvent = Display.class.getDeclaredMethod("sendEvent", int.class, Event.class);
            sendEvent.setAccessible(true);
            sendEvent.invoke(display, type, event);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(Observer.class))
            .overrideRuntimeConfigKey("quarkus.desktop.swt.startup-event.enabled", "true")
            .setCommandLineParameters("--verbose", FILE.toUri().toString(), "acme://open/42", "other://x",
                    FILE.resolveSibling("missing.acme").toString())
            .addBuildChainCustomizer(chain -> chain.addBuildStep(new BuildStep() {
                @Override
                public void execute(BuildContext context) {
                    context.produce(new OpenedTypesBuildItem(Set.of("acme"), Set.of("acme"), "org.acme.editor"));
                }
            }).produces(OpenedTypesBuildItem.class).build());

    @Test
    void firedWithTheArguments() throws InterruptedException {
        assertTrue(Observer.OPENED.await(10, TimeUnit.SECONDS), "OpenRequest not fired : " + Observer.EVENTS);
        assertTrue(Observer.onUiThread);
        assertEquals(List.of("SwtStartupEvent", "OpenRequest", "OpenRequest", "OpenRequest"), Observer.EVENTS);
        OpenRequest launch = Observer.REQUESTS.get(0);
        assertEquals(OpenRequest.Source.LAUNCH, launch.source());
        assertEquals(List.of(FILE), launch.files());
        assertEquals(List.of(URI.create("acme://open/42")), launch.uris());
        OpenRequest document = Observer.REQUESTS.get(1);
        assertEquals(OpenRequest.Source.SYSTEM, document.source());
        assertEquals(List.of(DOCUMENT), document.files());
        OpenRequest later = Observer.REQUESTS.get(2);
        assertEquals(OpenRequest.Source.SYSTEM, later.source());
        assertEquals(List.of(URI.create("acme://later")), later.uris());
    }

    /**
     * A file of the arguments : the same for the class loaders of the test and of the application.
     */
    private static Path createFile(String name) {
        try {
            Path file = Path.of("target", "open-request", name).toAbsolutePath().normalize();
            Files.createDirectories(file.getParent());
            Files.writeString(file, "notes");
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
