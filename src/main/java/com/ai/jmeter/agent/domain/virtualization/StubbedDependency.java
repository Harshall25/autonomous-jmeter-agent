package com.ai.jmeter.agent.domain.virtualization;

/**
 * A dependency to stand in for, and how the stand-in should behave.
 *
 * <p>Latency and failure rate are declared rather than recorded because the point of stubbing is
 * to make them controllable. A stub that always answers instantly and always succeeds produces a
 * load test that proves nothing: real dependencies are slow sometimes and broken sometimes, and
 * how the system under test copes with that is usually the question worth answering.
 *
 * @param name              what this dependency is called, used as the stub's identifier
 * @param urlPattern        the request path pattern the stub answers, as a regular expression
 * @param method            the HTTP method it answers
 * @param status            the status code to return
 * @param responseBody      the body to return
 * @param fixedDelayMillis  latency to simulate on every response
 * @param failurePercentage share of requests answered with a 500 instead, to exercise the caller's
 *                          error handling under load
 */
public record StubbedDependency(
        String name,
        String urlPattern,
        String method,
        int status,
        String responseBody,
        long fixedDelayMillis,
        int failurePercentage) {

    public StubbedDependency {
        responseBody = responseBody == null ? "" : responseBody;
        method = method == null || method.isBlank() ? "GET" : method;
        fixedDelayMillis = Math.max(0, fixedDelayMillis);
        failurePercentage = Math.clamp(failurePercentage, 0, 100);
    }

    /** A dependency that always answers correctly and instantly. */
    public static StubbedDependency alwaysHealthy(
            String name, String urlPattern, String method, String responseBody) {
        return new StubbedDependency(name, urlPattern, method, 200, responseBody, 0, 0);
    }

    /** @return {@code true} when this stub is configured to inject failures. */
    public boolean injectsFailures() {
        return failurePercentage > 0;
    }

    public String describe() {
        return "%s: %s %s -> %d%s%s".formatted(
                name, method, urlPattern, status,
                fixedDelayMillis > 0 ? " after %dms".formatted(fixedDelayMillis) : "",
                injectsFailures() ? ", %d%% failing".formatted(failurePercentage) : "");
    }
}
