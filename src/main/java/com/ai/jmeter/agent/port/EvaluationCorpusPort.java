package com.ai.jmeter.agent.port;

import com.ai.jmeter.agent.domain.eval.EvaluationSuite;
import java.nio.file.Path;

/**
 * Driven port: loads the golden corpus the agent is scored against.
 *
 * <p>A port rather than a hard-coded suite because the corpus is the asset. A team's captures are
 * their own systems' traffic, and the cases worth keeping are the ones that caught a regression
 * once already; those belong in their repository, not in this one.
 */
public interface EvaluationCorpusPort {

    /**
     * @param suiteFile the suite declaration
     * @return the suite to run
     * @throws EvaluationCorpusException if the suite cannot be read or is malformed
     */
    EvaluationSuite load(Path suiteFile);
}
