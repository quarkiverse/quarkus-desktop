package io.quarkiverse.desktop.packaging.deployment;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Builds the package of a format from the application image.
 */
public interface Packager {

    /**
     * Builds the package.
     *
     * @return the package, in the output directory of the context
     * @throws PackagingException when the tool of the format is missing or fails
     */
    Path build(PackagingContext context, ApplicationImage image) throws IOException;
}
