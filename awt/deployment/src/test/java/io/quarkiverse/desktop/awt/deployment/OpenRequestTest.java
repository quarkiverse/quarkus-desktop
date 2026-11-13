package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.awt.DesktopStartupEvent;
import io.quarkiverse.desktop.awt.OpenRequest;
import io.quarkiverse.desktop.packaging.spi.OpenedTypesBuildItem;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * {@code OpenRequest} is fired on the event dispatch thread after {@code DesktopStartupEvent}, with the program arguments
 * of the file types and URL schemes of the packages ({@code OpenedTypesBuildItem}, here produced by a build step of the
 * test, as the Desktop Packaging extension does). The other arguments are left to the application. Needs a display.
 */
class OpenRequestTest {

    static final Path FILE = createFile();

    @Singleton
    public static class Observer {

        final List<String> events = new CopyOnWriteArrayList<>();
        final List<OpenRequest> requests = new CopyOnWriteArrayList<>();
        final CountDownLatch opened = new CountDownLatch(1);
        volatile boolean onEventDispatchThread;

        void onDesktopStart(@Observes DesktopStartupEvent event) {
            events.add("DesktopStartupEvent");
        }

        void open(@Observes OpenRequest request) {
            onEventDispatchThread = EventQueue.isDispatchThread();
            events.add("OpenRequest");
            requests.add(request);
            opened.countDown();
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(Observer.class))
            .overrideRuntimeConfigKey("quarkus.desktop.awt.startup-event.enabled", "true")
            .setCommandLineParameters("--verbose", FILE.toString(), "acme://open/42", "other://x",
                    FILE.resolveSibling("missing.acme").toString())
            .addBuildChainCustomizer(chain -> chain.addBuildStep(new BuildStep() {
                @Override
                public void execute(BuildContext context) {
                    context.produce(new OpenedTypesBuildItem(Set.of("acme"), Set.of("acme"), "org.acme.editor"));
                }
            }).produces(OpenedTypesBuildItem.class).build());

    @Inject
    Observer observer;

    @Test
    void firedWithTheArguments() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        assertTrue(observer.opened.await(10, TimeUnit.SECONDS), "OpenRequest not fired");
        EventQueue.invokeAndWait(() -> {
        });
        assertTrue(observer.onEventDispatchThread);
        assertEquals(List.of("DesktopStartupEvent", "OpenRequest"), observer.events);
        OpenRequest request = observer.requests.get(0);
        assertEquals(OpenRequest.Source.LAUNCH, request.source());
        assertEquals(List.of(FILE.toAbsolutePath().normalize()), request.files());
        assertEquals(List.of(URI.create("acme://open/42")), request.uris());
    }

    /**
     * The file of the argument : the same for the class loaders of the test and of the application.
     */
    private static Path createFile() {
        try {
            Path file = Path.of("target", "open-request", "Notes.ACME").toAbsolutePath();
            Files.createDirectories(file.getParent());
            Files.writeString(file, "notes");
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
