package io.quarkiverse.desktop.swt;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.interceptor.InterceptorBinding;

/**
 * Runs the annotated methods of a bean on the user interface thread, whatever thread calls them.
 * <ul>
 * <li>A {@code void} method runs now when called on the user interface thread (its exception goes to the caller), later
 * ({@code Display.asyncExec}) otherwise, and its call returns at once ; an exception thrown later is logged by the
 * event loop.</li>
 * <li>A method returning a {@code CompletionStage} (or a {@code CompletableFuture}) returns a stage completed with the
 * result, or the exception, of the method, once it ran on the user interface thread.</li>
 * </ul>
 * A call before the user interface starts runs once it started, and is rejected when the application stops before ;
 * a call once the user interface stopped is rejected. A rejected call is skipped, its {@code CompletionStage} completes
 * with a {@code RejectedExecutionException}. Other return types are a build error, as are beans other than
 * {@code @ApplicationScoped}, {@code @Singleton} and {@code @Dependent} ones, and a binding on a widget class. The
 * interceptor is the outermost one : the other interceptors of the method ({@code @ActivateRequestContext},
 * {@code @Transactional}, fault tolerance, caches, tracing) run on the user interface thread.
 *
 * <pre>
 * &#64;ApplicationScoped
 * public class StatusPresenter {
 *
 *     &#64;Inject
 *     MainWindow window;
 *
 *     &#64;RunOnUiThread
 *     void show(String text) {
 *         window.status().setText(text);
 *     }
 * }
 * </pre>
 */
@InterceptorBinding
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface RunOnUiThread {
}
