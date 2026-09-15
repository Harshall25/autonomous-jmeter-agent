package com.ai.jmeter.agent.domain.cost;

/**
 * Tokens consumed by one model call.
 *
 * @param promptTokens     tokens sent
 * @param completionTokens tokens generated
 */
public record TokenUsage(long promptTokens, long completionTokens) {

    /** Used when a provider reports no usage, so accounting degrades rather than breaking. */
    public static final TokenUsage UNKNOWN = new TokenUsage(0, 0);

    public TokenUsage {
        promptTokens = Math.max(promptTokens, 0);
        completionTokens = Math.max(completionTokens, 0);
    }

    public long total() {
        return promptTokens + completionTokens;
    }

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(
                promptTokens + other.promptTokens,
                completionTokens + other.completionTokens);
    }
}
