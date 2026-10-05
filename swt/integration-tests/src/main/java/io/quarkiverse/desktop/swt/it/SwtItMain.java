package io.quarkiverse.desktop.swt.it;

import java.nio.file.Path;

import jakarta.inject.Inject;

import org.eclipse.swt.widgets.Display;

import io.quarkiverse.desktop.swt.SwtLifecycle;
import io.quarkus.runtime.ImageMode;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Runs the scenario named by the first argument on the user interface of the extension ({@link SwtLifecycle#run()} on
 * the main thread), prints its results, and exits with 1 when a check failed.
 * <p>
 * Scenarios :
 * <ul>
 * <li>{@code swt [directory]} : the SWT checks ({@link SwtChecks}). The rendered images are written to the directory
 * (the temporary directory by default).</li>
 * <li>{@code lifecycle} : the application model of the extension ({@link LifecycleChecks}) : the startup event, the user
 * interface thread, the executor, {@code @RunOnUiThread}, the widget beans, the quit requests, the shutdown.</li>
 * <li>{@code exit-policy} : a shell opens and closes, and the application stops by itself (not in tests, where the policy
 * is disabled).</li>
 * </ul>
 */
@QuarkusMain
public class SwtItMain implements QuarkusApplication {

    @Inject
    SwtLifecycle lifecycle;

    @Inject
    ItUserInterface userInterface;

    @Inject
    LifecycleChecks lifecycleChecks;

    @Override
    public int run(String... args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "swt";
        String mode = ImageMode.current().isNativeImage() ? "native" : "jvm";
        switch (scenario) {
            case "swt" -> {
                Path directory = Path.of(args.length > 1 ? args[1] : System.getProperty("java.io.tmpdir"));
                userInterface.start(scenario, display -> new SwtChecks(directory, mode).run(display));
            }
            case "lifecycle" -> userInterface.start(scenario, display -> lifecycleChecks.run(display, userInterface));
            case "exit-policy" -> userInterface.start(scenario, null);
            default -> {
                System.out.println("Unknown scenario " + scenario);
                return 2;
            }
        }
        userInterface.mainThread(Thread.currentThread());
        lifecycle.run();
        Display display = userInterface.display();
        System.out.println("RESULT lifecycle-run-returned OK disposed=" + (display != null && display.isDisposed()));
        return userInterface.exitCode();
    }
}
