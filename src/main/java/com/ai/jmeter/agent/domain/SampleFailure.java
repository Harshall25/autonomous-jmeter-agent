package com.ai.jmeter.agent.domain;

/**
 * One failed row extracted from a JMeter {@code .jtl} result file.
 *
 * <p>These are fed verbatim back to the model during self-healing — the response code and
 * failure message are the evidence the model needs to work out what it got wrong.
 *
 * @param label           the sampler name as declared in the test plan
 * @param responseCode    the HTTP status or JDBC error code JMeter recorded
 * @param responseMessage the accompanying status line
 * @param failureMessage  the assertion failure text, when an assertion produced the failure
 */
public record SampleFailure(
        String label,
        String responseCode,
        String responseMessage,
        String failureMessage) {

    /** Defaults every field to empty so {@link #describe()} is always safe to call. */
    public SampleFailure {
        label = label == null ? "" : label;
        responseCode = responseCode == null ? "" : responseCode;
        responseMessage = responseMessage == null ? "" : responseMessage;
        failureMessage = failureMessage == null ? "" : failureMessage;
    }

    /**
     * @return a single-line, human- and model-readable rendering used to build the error
     * digest handed to the healing prompt
     */
    public String describe() {
        StringBuilder description = new StringBuilder()
                .append("sampler='").append(label).append('\'')
                .append(" responseCode=").append(responseCode)
                .append(" responseMessage='").append(responseMessage).append('\'');
        if (!failureMessage.isBlank()) {
            description.append(" failureMessage='").append(failureMessage).append('\'');
        }
        return description.toString();
    }
}
