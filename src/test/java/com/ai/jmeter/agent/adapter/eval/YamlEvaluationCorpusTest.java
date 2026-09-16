package com.ai.jmeter.agent.adapter.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.eval.EvaluationSuite;
import com.ai.jmeter.agent.domain.eval.StructuralExpectation;
import com.ai.jmeter.agent.port.EvaluationCorpusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("YamlEvaluationCorpus")
class YamlEvaluationCorpusTest {

    @TempDir
    Path corpusRoot;

    private YamlEvaluationCorpus corpus;

    @BeforeEach
    void setUp() {
        corpus = new YamlEvaluationCorpus(new ObjectMapper(new YAMLFactory()));
    }

    private Path suite(String yaml) throws IOException {
        Path suiteFile = corpusRoot.resolve("suite.yaml");
        Files.writeString(suiteFile, yaml);
        return suiteFile;
    }

    @Test
    @DisplayName("reads a suite of cases with the expectations each declares")
    void readsASuite() throws IOException {
        EvaluationSuite loaded = corpus.load(suite("""
                revision: "prompts@v3 / sonnet"
                cases:
                  - name: checkout
                    mode: API
                    source: captures/checkout.har
                    workload: telemetry/access.log
                    expectations:
                      - type: sampler
                        value: login
                      - type: variable
                        value: auth_token
                      - type: csvColumn
                        value: user
                      - type: minSamplers
                        value: "3"
                      - type: noUnresolvedVariables
                  - name: slow-queries
                    mode: SQL
                    source: captures/slow.log
                """));

        assertThat(loaded.revision()).isEqualTo("prompts@v3 / sonnet");
        assertThat(loaded.cases()).hasSize(2);
        assertThat(loaded.cases().get(0).expectations()).containsExactly(
                new StructuralExpectation.ExercisesSampler("login"),
                new StructuralExpectation.CorrelatesVariable("auth_token"),
                new StructuralExpectation.ParameterizesColumn("user"),
                new StructuralExpectation.CoversAtLeast(3),
                new StructuralExpectation.ResolvesEveryVariable());
        assertThat(loaded.cases().get(1).mode()).isEqualTo(ExecutionMode.SQL);
        assertThat(loaded.cases().get(1).telemetry()).isNull();
    }

    @Test
    @DisplayName("resolves captures against the suite's own directory")
    void resolvesPathsRelativeToTheSuite() throws IOException {
        // A corpus checked out anywhere has to work without editing every case.
        EvaluationSuite loaded = corpus.load(suite("""
                revision: v1
                cases:
                  - name: checkout
                    mode: API
                    source: captures/checkout.har
                    workload: telemetry/access.log
                """));

        assertThat(loaded.cases().get(0).sourceFile())
                .isEqualTo(corpusRoot.toAbsolutePath().resolve("captures/checkout.har"));
        assertThat(loaded.cases().get(0).telemetry())
                .isEqualTo(corpusRoot.toAbsolutePath().resolve("telemetry/access.log"));
    }

    @Test
    @DisplayName("accepts a case that asserts nothing beyond producing a working plan")
    void acceptsACaseWithNoExpectations() throws IOException {
        EvaluationSuite loaded = corpus.load(suite("""
                cases:
                  - name: smoke
                    mode: API
                    source: a.har
                """));

        assertThat(loaded.revision()).isEqualTo("unlabelled revision");
        assertThat(loaded.cases().get(0).expectations()).isEmpty();
    }

    @Test
    @DisplayName("refuses an unknown expectation rather than silently dropping it")
    void refusesAnUnknownExpectation() throws IOException {
        // A typo that drops an assertion turns the corpus into a suite that passes because it
        // stopped checking.
        Path suiteFile = suite("""
                cases:
                  - name: checkout
                    mode: API
                    source: a.har
                    expectations:
                      - type: samplr
                        value: login
                """);

        assertThatThrownBy(() -> corpus.load(suiteFile))
                .isInstanceOf(EvaluationCorpusException.class)
                .hasMessageContaining("Unknown expectation type 'samplr'")
                .hasMessageContaining("noUnresolvedVariables");
    }

    @Test
    @DisplayName("refuses an expectation with no value to check against")
    void refusesAValuelessExpectation() throws IOException {
        Path suiteFile = suite("""
                cases:
                  - name: checkout
                    mode: API
                    source: a.har
                    expectations:
                      - type: sampler
                """);

        assertThatThrownBy(() -> corpus.load(suiteFile))
                .isInstanceOf(EvaluationCorpusException.class)
                .hasMessageContaining("needs a value");
    }

    @Test
    @DisplayName("refuses a coverage expectation that is not a number")
    void refusesANonNumericCount() throws IOException {
        Path suiteFile = suite("""
                cases:
                  - name: checkout
                    mode: API
                    source: a.har
                    expectations:
                      - type: minSamplers
                        value: several
                """);

        assertThatThrownBy(() -> corpus.load(suiteFile))
                .isInstanceOf(EvaluationCorpusException.class)
                .hasMessageContaining("needs a whole number, got 'several'");
    }

    @Test
    @DisplayName("refuses a case that names no capture")
    void refusesACaseWithNoSource() throws IOException {
        Path suiteFile = suite("""
                cases:
                  - name: checkout
                    mode: API
                """);

        assertThatThrownBy(() -> corpus.load(suiteFile))
                .isInstanceOf(EvaluationCorpusException.class)
                .hasMessageContaining("names no capture");
    }

    @Test
    @DisplayName("refuses a case whose capture path is blank")
    void refusesABlankSource() throws IOException {
        Path suiteFile = suite("""
                cases:
                  - name: checkout
                    mode: API
                    source: "   "
                """);

        assertThatThrownBy(() -> corpus.load(suiteFile))
                .isInstanceOf(EvaluationCorpusException.class)
                .hasMessageContaining("names no capture");
    }

    @Test
    @DisplayName("refuses an expectation whose value is blank")
    void refusesABlankExpectationValue() throws IOException {
        Path suiteFile = suite("""
                cases:
                  - name: checkout
                    mode: API
                    source: a.har
                    expectations:
                      - type: variable
                        value: "  "
                """);

        assertThatThrownBy(() -> corpus.load(suiteFile))
                .isInstanceOf(EvaluationCorpusException.class)
                .hasMessageContaining("needs a value");
    }

    @Test
    @DisplayName("refuses an expectation that declares no type")
    void refusesATypelessExpectation() throws IOException {
        Path suiteFile = suite("""
                cases:
                  - name: checkout
                    mode: API
                    source: a.har
                    expectations:
                      - value: login
                """);

        assertThatThrownBy(() -> corpus.load(suiteFile))
                .isInstanceOf(EvaluationCorpusException.class)
                .hasMessageContaining("Unknown expectation type 'null'");
    }

    @Test
    @DisplayName("refuses a case whose mode the agent has no parser for")
    void refusesAnUnknownMode() throws IOException {
        Path suiteFile = suite("""
                cases:
                  - name: checkout
                    mode: GRPC
                    source: a.har
                """);

        assertThatThrownBy(() -> corpus.load(suiteFile))
                .isInstanceOf(EvaluationCorpusException.class)
                .hasMessageContaining("unknown mode 'GRPC'");
    }

    @Test
    @DisplayName("refuses a case that declares no mode at all")
    void refusesAMissingMode() throws IOException {
        Path suiteFile = suite("""
                cases:
                  - name: checkout
                    source: a.har
                """);

        assertThatThrownBy(() -> corpus.load(suiteFile))
                .isInstanceOf(EvaluationCorpusException.class)
                .hasMessageContaining("unknown mode");
    }

    @Test
    @DisplayName("refuses a suite with nothing in it to measure")
    void refusesAnEmptySuite() throws IOException {
        Path empty = suite("revision: v1\n");
        Path noCases = corpusRoot.resolve("none.yaml");
        Files.writeString(noCases, "revision: v1\ncases: []\n");

        assertThatThrownBy(() -> corpus.load(empty))
                .isInstanceOf(EvaluationCorpusException.class)
                .hasMessageContaining("declares no cases");
        assertThatThrownBy(() -> corpus.load(noCases))
                .isInstanceOf(EvaluationCorpusException.class)
                .hasMessageContaining("declares no cases");
    }

    @Test
    @DisplayName("refuses a suite file that is not there or is not YAML")
    void refusesAnUnreadableSuite() throws IOException {
        Path missing = corpusRoot.resolve("absent.yaml");
        Path malformed = corpusRoot.resolve("bad.yaml");
        Files.writeString(malformed, "cases: [ unterminated\n");

        assertThatThrownBy(() -> corpus.load(missing))
                .isInstanceOf(EvaluationCorpusException.class)
                .hasMessageContaining("Unable to read the suite");
        assertThatThrownBy(() -> corpus.load(malformed))
                .isInstanceOf(EvaluationCorpusException.class)
                .hasMessageContaining("Unable to read the suite");
    }

    @Test
    @DisplayName("treats a document that parses to nothing as declaring no cases")
    void refusesANullDocument() throws IOException {
        Path nullDocument = suite("--- null\n");

        assertThatThrownBy(() -> corpus.load(nullDocument))
                .isInstanceOf(EvaluationCorpusException.class)
                .hasMessageContaining("declares no cases");
    }

    @Test
    @DisplayName("treats an entirely blank suite as unreadable")
    void refusesABlankSuite() throws IOException {
        Path blank = suite("");

        assertThatThrownBy(() -> corpus.load(blank))
                .isInstanceOf(EvaluationCorpusException.class)
                .hasMessageContaining("Unable to read the suite");
    }
}
