package io.quarkiverse.desktop.awt.runtime;

import java.awt.EventQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.awt.RunOnEdt;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;

/**
 * Runs the methods annotated with {@link RunOnEdt} on the event dispatch thread. The outermost interceptor (before the
 * {@code PLATFORM_BEFORE} ones), so that the other interceptors run on the event dispatch thread around the method.
 */
@RunOnEdt
@Interceptor
@Priority(RunOnEdtInterceptor.PRIORITY)
public class RunOnEdtInterceptor {

    static final int PRIORITY = Interceptor.Priority.PLATFORM_BEFORE - 100;

    private static final Logger LOGGER = Logger.getLogger(RunOnEdtInterceptor.class);

    @AroundInvoke
    Object runOnEdt(InvocationContext context) throws Exception {
        if (EventQueue.isDispatchThread()) {
            if (CompletionStage.class.isAssignableFrom(context.getMethod().getReturnType())) {
                try {
                    return context.proceed();
                } catch (Throwable e) {
                    return CompletableFuture.failedFuture(e);
                }
            }
            return context.proceed();
        }
        // the container of the bean : after a restart in dev mode, a call queued by the previous application is skipped
        ArcContainer container = Arc.container();
        if (CompletionStage.class.isAssignableFrom(context.getMethod().getReturnType())) {
            CompletableFuture<Object> result = new CompletableFuture<>();
            EventQueue.invokeLater(() -> {
                if (!isRunning(container)) {
                    result.completeExceptionally(new IllegalStateException("The application stopped before "
                            + context.getMethod() + " ran on the event dispatch thread"));
                    return;
                }
                try {
                    CompletionStage<?> stage = (CompletionStage<?>) context.proceed();
                    if (stage == null) {
                        result.complete(null);
                    } else {
                        stage.whenComplete((value, error) -> {
                            if (error != null) {
                                result.completeExceptionally(error);
                            } else {
                                result.complete(value);
                            }
                        });
                    }
                } catch (Throwable e) {
                    result.completeExceptionally(e);
                }
            });
            return result;
        }
        EventQueue.invokeLater(() -> {
            if (!isRunning(container)) {
                LOGGER.debugf("The application stopped before %s ran on the event dispatch thread", context.getMethod());
                return;
            }
            try {
                context.proceed();
            } catch (Exception e) {
                // to the uncaught exception handler of the event dispatch thread
                throw sneakyThrow(e);
            }
        });
        return null;
    }

    private static boolean isRunning(ArcContainer container) {
        return container != null && container.isRunning();
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }
}
