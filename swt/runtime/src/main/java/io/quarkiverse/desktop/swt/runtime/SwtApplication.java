package io.quarkiverse.desktop.swt.runtime;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.quarkiverse.desktop.swt.SwtLifecycle;
import io.quarkus.runtime.QuarkusApplication;

/**
 * The main of an application observing {@code SwtStartupEvent} without a {@code @QuarkusMain} : runs the user interface
 * on the main thread ({@link SwtLifecycle#run()}) until the application stops.
 */
@Singleton
public class SwtApplication implements QuarkusApplication {

    @Inject
    SwtLifecycle lifecycle;

    @Override
    public int run(String... args) {
        lifecycle.run();
        // the exit code of Quarkus.asyncExit(code), when set, wins
        return 0;
    }
}
