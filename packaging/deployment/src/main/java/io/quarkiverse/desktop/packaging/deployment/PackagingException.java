package io.quarkiverse.desktop.packaging.deployment;

/**
 * A package that cannot be built : a missing tool, a tool that failed, a configuration that a format rejects. Its
 * message tells what to install or to change.
 */
public class PackagingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PackagingException(String message) {
        super(message);
    }

    public PackagingException(String message, Throwable cause) {
        super(message, cause);
    }
}
