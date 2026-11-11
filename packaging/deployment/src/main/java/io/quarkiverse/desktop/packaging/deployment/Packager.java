package io.quarkiverse.desktop.packaging.deployment;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Builds the package of a format from the application image.
 */
public interface Packager {

    /**
     * Checks what the package needs, before the application image and the first package are built : the tools of the
     * format, and what the format derives from the configuration (its names, its version), without the application
     * image. {@link #build} checks them again.
     *
     * @throws PackagingException when the package cannot be built
     */
    default void check(PackagingContext context) {
    }

    /**
     * Builds the package.
     *
     * @return the package, in the output directory of the context
     * @throws PackagingException when the tool of the format is missing or fails
     */
    Path build(PackagingContext context, ApplicationImage image) throws IOException;
}
