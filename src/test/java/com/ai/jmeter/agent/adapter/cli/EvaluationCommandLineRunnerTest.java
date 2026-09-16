package com.ai.jmeter.agent.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.eval.CaseResult;
import com.ai.jmeter.agent.domain.eval.EvaluationCase;
import com.ai.jmeter.agent.domain.eval.EvaluationScore;
import com.ai.jmeter.agent.domain.eval.EvaluationSuite;
import com.ai.jmeter.agent.orchestrator.EvaluationHarness;
import com.ai.jmeter.agent.port.EvaluationCorpusPort;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EvaluationCommandLineRunner")
class EvaluationCommandLineRunnerTest {

    @Mock
    private EvaluationCorpusPort corpus;

    @Mock
    private EvaluationHarness harness;

    private EvaluationCommandLineRunner runner;

    @BeforeEach
    void setUp() {
        runner = new EvaluationCommandLineRunner(corpus, harness);
    }

    private static EvaluationSuite suite() {
        return new EvaluationSuite("prompts@v3", List.of(new EvaluationCase(
                "checkout", ExecutionMode.API, Path.of("a.har"), null, List.of())));
    }

    @Test
    @DisplayName("loads the suite it was pointed at and scores the agent against it")
    void runsTheSuite() {
        when(corpus.load(any())).thenReturn(suite());
        when(harness.evaluate(any())).thenReturn(new EvaluationScore("prompts@v3", List.of(
                new CaseResult("checkout", true, 1, 1200, 0, List.of(), ""))));

        runner.run("--evaluate=corpus/suite.yaml");

        ArgumentCaptor<Path> suiteFile = ArgumentCaptor.forClass(Path.class);
        verify(corpus).load(suiteFile.capture());
        assertThat(suiteFile.getValue()).isEqualTo(Path.of("corpus/suite.yaml"));
        verify(harness).evaluate(any());
    }

    @Test
    @DisplayName("stays out of the way of an ordinary load test")
    void inertWithoutTheArgument() {
        runner.run("--mode=API", "--source=a.har");

        verifyNoInteractions(corpus, harness);
    }

    @Test
    @DisplayName("treats an empty suite path as no suite at all")
    void blankSuitePathIsIgnored() {
        runner.run("--evaluate=");

        verifyNoInteractions(corpus, harness);
    }
}
