package io.quarkiverse.desktop.swt.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;

import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import org.jboss.logging.Logger;

import io.quarkiverse.desktop.swt.RunOnUiThread;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;

/**
 * Runs the methods annotated with {@link RunOnUiThread} on the user interface thread. The outermost interceptor (before
 * the {@code PLATFORM_BEFORE} ones), so that the other interceptors run on the user interface thread around the method.
 */
@RunOnUiThread
@Interceptor
@Priority(RunOnUiThreadInterceptor.PRIORITY)
public class RunOnUiThreadInterceptor {

    static final int PRIORITY = Interceptor.Priority.PLATFORM_BEFORE - 100;

    private static final Logger LOGGER = Logger.getLogger(RunOnUiThreadInterceptor.class);

    private static final Logger UI_THREAD_LOGGER = Logger.getLogger(SwtUi.UI_THREAD_CATEGORY);

    @AroundInvoke
    Object runOnUiThread(InvocationContext context) throws Exception {
        boolean stage = CompletionStage.class.isAssignableFrom(context.getMethod().getReturnType());
        if (SwtUi.isUiThread()) {
            if (stage) {
                try {
                    Object returned = context.proceed();
                    // as when called on another thread
                    return returned != null ? returned : CompletableFuture.completedFuture(null);
                } catch (Throwable e) {
                    return CompletableFuture.failedFuture(e);
                }
            }
            return context.proceed();
        }
        // the container of the bean : a call that runs once its application stopped (a dev mode restart) is skipped
        ArcContainer container = Arc.container();
        if (stage) {
            CompletableFuture<Object> result = new CompletableFuture<>();
            try {
                SwtUi.post(new SwtUi.Rejectable() {

                    @Override
                    public void run() {
                        if (!isRunning(container)) {
                            result.completeExceptionally(new IllegalStateException("The application stopped before "
                                    + context.getMethod() + " ran on the user interface thread"));
                            return;
                        }
                        try {
                            CompletionStage<?> returned = (CompletionStage<?>) context.proceed();
                            if (returned == null) {
                                result.complete(null);
                            } else {
                                returned.whenComplete((value, error) -> {
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
                    }

                    @Override
                    public void reject(RejectedExecutionException cause) {
                        result.completeExceptionally(cause);
                    }
                });
            } catch (RejectedExecutionException e) {
                result.completeExceptionally(e);
            }
            return result;
        }
        try {
            SwtUi.post(new SwtUi.Rejectable() {

                @Override
                public void run() {
                    if (!isRunning(container)) {
                        LOGGER.debugf("The application stopped before %s ran on the user interface thread",
                                context.getMethod());
                        return;
                    }
                    try {
                        context.proceed();
                    } catch (RuntimeException e) {
                        // to the exception handler of the Display, which logs it
                        throw e;
                    } catch (Exception e) {
                        UI_THREAD_LOGGER.errorf(e, "Uncaught exception in the user interface thread %s",
                                Thread.currentThread().getName());
                    }
                }

                @Override
                public void reject(RejectedExecutionException cause) {
                    LOGGER.debugf("%s is skipped : %s", context.getMethod(), cause.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            LOGGER.debugf("%s is skipped : %s", context.getMethod(), e.getMessage());
        }
        return null;
    }

    private static boolean isRunning(ArcContainer container) {
        return container != null && container.isRunning();
    }
}
