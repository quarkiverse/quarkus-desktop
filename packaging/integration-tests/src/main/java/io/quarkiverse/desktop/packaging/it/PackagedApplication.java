package io.quarkiverse.desktop.packaging.it;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * The packaged application of the integration tests : it has no user interface, as the packages are the same for the
 * applications of every toolkit. Started by the launcher of the application image with {@code --marker=<file>}, it
 * writes that it started in the file, with its version and its other arguments, and exits.
 */
@QuarkusMain
public class PackagedApplication implements QuarkusApplication {

    static final String MARKER_OPTION = "--marker=";

    @Override
    public int run(String... args) throws Exception {
        Path marker = null;
        List<String> others = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith(MARKER_OPTION)) {
                marker = Path.of(arg.substring(MARKER_OPTION.length()));
            } else {
                others.add(arg);
            }
        }
        String version = ConfigProvider.getConfig().getValue("quarkus.application.version", String.class);
        String line = "started " + version + " " + others;
        if (marker != null) {
            Files.writeString(marker, line);
        } else {
            System.out.println(line);
        }
        return 0;
    }
}
