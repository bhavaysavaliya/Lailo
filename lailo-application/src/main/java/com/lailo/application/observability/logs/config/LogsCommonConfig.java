package com.lailo.application.observability.logs.config;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;


/**
 * Centralized logging aspect that intercepts method invocations across the
 * application's controller, service, and repository layers.
 *
 * <p>This aspect uses Spring AOP (backed by AspectJ annotations) to weave
 * consistent entry/exit/error logging around every public method in classes
 * annotated with {@link org.springframework.web.bind.annotation.RestController},
 * {@link org.springframework.stereotype.Service}, or
 * {@link org.springframework.stereotype.Repository}, without requiring any
 * manual logging code inside those classes.</p>
 *
 * <p>For each intercepted method invocation, this aspect logs:</p>
 * <ul>
 *     <li>Method entry, including arguments (at DEBUG level) or a simple
 *         call notice (at INFO level)</li>
 *     <li>Method completion, including elapsed execution time in milliseconds
 *         and, at DEBUG level, the returned value</li>
 *     <li>Method failure, including elapsed time, exception type, exception
 *         message, and the original method arguments, before the exception
 *         is rethrown to preserve normal exception propagation</li>
 * </ul>
 *
 * <p>The logger used for each invocation is obtained dynamically from the
 * join point's target class (see {@link #logAround(ProceedingJoinPoint)}),
 * so log output is attributed to the actual intercepted class rather than
 * to this aspect class.</p>
 *
 * @author Bhavay
 * @see org.aspectj.lang.annotation.Aspect
 * @see org.aspectj.lang.ProceedingJoinPoint
 */
@Aspect
@Component
public class LogsCommonConfig {

    /**
     * Pointcut matching all methods declared within classes annotated with
     * {@link org.springframework.web.bind.annotation.RestController @RestController}.
     *
     * <p>This targets the web/controller layer, capturing incoming HTTP
     * request handling methods.</p>
     */
    @Pointcut("within(@org.springframework.web.bind.annotation.RestController *)")
    public void restControllerClasses() {}

    /**
     * Pointcut matching all methods declared within classes annotated with
     * {@link org.springframework.stereotype.Service @Service}.
     *
     * <p>This targets the business/service layer, capturing core application
     * logic invocations.</p>
     */
    @Pointcut("within(@org.springframework.stereotype.Service *)")
    public void serviceClasses() {}

    /**
     * Pointcut matching all methods declared within classes annotated with
     * {@link org.springframework.stereotype.Repository @Repository}.
     *
     * <p>This targets the data access layer, capturing persistence and
     * query operations.</p>
     */
    @Pointcut("within(@org.springframework.stereotype.Repository *)")
    public void repositoryClasses() {}

    /**
     * Advice that wraps every method matched by {@link #restControllerClasses()},
     * {@link #serviceClasses()}, or {@link #repositoryClasses()} with
     * structured entry, completion, and error logging.
     *
     * <p>Behavior:</p>
     * <ol>
     *     <li>Resolves a {@link Logger} scoped to the join point's actual
     *         runtime target class (via {@link ProceedingJoinPoint#getTarget()}),
     *         so that log lines are attributed to the real intercepted class
     *         rather than to this aspect.</li>
     *     <li>Logs method entry before invocation — including arguments when
     *         DEBUG level is enabled, or a simpler message at INFO level
     *         otherwise.</li>
     *     <li>Invokes the original method via {@link ProceedingJoinPoint#proceed()},
     *         timing its execution in milliseconds.</li>
     *     <li>On successful completion, logs elapsed time and, at DEBUG level,
     *         the returned result.</li>
     *     <li>On failure, logs elapsed time, exception type, exception message,
     *         and original arguments at ERROR level, then rethrows the
     *         original {@link Throwable} unchanged so normal exception
     *         handling (e.g. {@code @ControllerAdvice}) is unaffected.</li>
     * </ol>
     *
     * @param joinPoint the {@link ProceedingJoinPoint} representing the
     *                   intercepted method invocation; provides access to
     *                   the target object, method signature, and arguments,
     *                   and allows the original method to be invoked via
     *                   {@link ProceedingJoinPoint#proceed()}
     * @return the result returned by the intercepted method, unchanged
     * @throws Throwable any exception thrown by the intercepted method,
     *                    rethrown after being logged
     */
    @Around("restControllerClasses() || serviceClasses() || repositoryClasses()")
    public Object logAround(ProceedingJoinPoint joinPoint) throws Throwable {
        Logger log = LoggerFactory.getLogger(joinPoint.getTarget().getClass());
        String className = joinPoint.getSignature().getDeclaringTypeName();
        String methodName = joinPoint.getSignature().getName();

        if (log.isDebugEnabled()) {
            log.debug("→ {}.{}() called with args={}", className, methodName, Arrays.toString(joinPoint.getArgs()));
        } else {
            log.info("→ {}.{}() called", className, methodName);
        }

        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            long elapsed = System.currentTimeMillis() - start;

            if (log.isDebugEnabled()) {
                log.debug("✔ {}.{}() completed in {}ms, returned={}", className, methodName, elapsed, result);
            } else {
                log.info("✔ {}.{}() completed in {}ms", className, methodName, elapsed);
            }
            return result;

        } catch (Throwable ex) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("✘ {}.{}() failed in {}ms with {}: {} | args={}",
                    className, methodName, elapsed, ex.getClass().getSimpleName(), ex.getMessage(),
                    Arrays.toString(joinPoint.getArgs()), ex);
            throw ex;
        }
    }
}
