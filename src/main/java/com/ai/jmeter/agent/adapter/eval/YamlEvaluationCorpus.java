package com.ai.jmeter.agent.adapter.eval;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.eval.EvaluationCase;
import com.ai.jmeter.agent.domain.eval.EvaluationSuite;
import com.ai.jmeter.agent.domain.eval.StructuralExpectation;
import com.ai.jmeter.agent.port.EvaluationCorpusException;
import com.ai.jmeter.agent.port.EvaluationCorpusPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Driven adapter: reads a golden corpus from a YAML suite alongside the captures it names.
 *
 * <p>YAML rather than Java so the corpus lives in the repository of the service being tested,
 * next to its own HAR captures, and can be extended by whoever hit the regression rather than by
 * whoever maintains this agent.
 *
 * <p>Paths in a suite resolve against the suite file's own directory, so a corpus checked out
 * anywhere works without editing every case.
 */
public final class YamlEvaluationCorpus implements EvaluationCorpusPort {

    private static final Logger log = LoggerFactory.getLogger(YamlEvaluationCorpus.class);

    private final ObjectMapper yamlMapper;

    public YamlEvaluationCorpus(ObjectMapper yamlMapper) {
        this.yamlMapper = yamlMapper;
    }

    @Override
    public EvaluationSuite load(Path suiteFile) {
        SuiteFile parsed;
        try {
            parsed = yamlMapper.readValue(suiteFile.toFile(), SuiteFile.class);
        } catch (IOException e) {
            throw new EvaluationCorpusException("Unable to read the suite at " + suiteFile, e);
        }
        if (parsed == null || parsed.cases() == null || parsed.cases().isEmpty()) {
            throw new EvaluationCorpusException(
                    "The suite at " + suiteFile + " declares no cases");
        }

        Path root = suiteFile.toAbsolutePath().getParent();
        List<EvaluationCase> cases = parsed.cases().stream()
                .map(declared -> toDomain(declared, root))
                .toList();

        log.info("Loaded {} evaluation case(s) from {}", cases.size(), suiteFile);
        return new EvaluationSuite(parsed.revision(), cases);
    }

    private static EvaluationCase toDomain(CaseFile declared, Path root) {
        if (declared.source() == null || declared.source().isBlank()) {
            throw new EvaluationCorpusException(
                    "Case '%s' names no capture to run".formatted(declared.name()));
        }
        return new EvaluationCase(
                declared.name(),
                modeOf(declared),
                root.resolve(declared.source()),
                declared.workload() == null ? null : root.resolve(declared.workload()),
                expectationsOf(declared));
    }

    private static ExecutionMode modeOf(CaseFile declared) {
        try {
            return ExecutionMode.valueOf(declared.mode().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new EvaluationCorpusException(
                    "Case '%s' declares an unknown mode '%s'".formatted(
                            declared.name(), declared.mode()), e);
        }
    }

    private static List<StructuralExpectation> expectationsOf(CaseFile declared) {
        if (declared.expectations() == null) {
            return List.of();
        }
        return declared.expectations().stream().map(YamlEvaluationCorpus::toExpectation).toList();
    }

    /**
     * Narrows a declared expectation into the sealed domain type.
     *
     * <p>An unknown kind is refused rather than skipped: a typo that silently drops an assertion
     * turns the corpus into a suite that passes because it stopped checking.
     */
    private static StructuralExpectation toExpectation(ExpectationFile declared) {
        String kind = declared.type() == null ? "" : declared.type().trim();
        return switch (kind) {
            case "sampler" -> new StructuralExpectation.ExercisesSampler(required(declared));
            case "variable" -> new StructuralExpectation.CorrelatesVariable(required(declared));
            case "csvColumn" -> new StructuralExpectation.ParameterizesColumn(required(declared));
            case "minSamplers" -> new StructuralExpectation.CoversAtLeast(count(declared));
            case "noUnresolvedVariables" -> new StructuralExpectation.ResolvesEveryVariable();
            default -> throw new EvaluationCorpusException(
                    "Unknown expectation type '%s'. Valid types are sampler, variable, "
                            .formatted(declared.type())
                            + "csvColumn, minSamplers and noUnresolvedVariables.");
        };
    }

    private static String required(ExpectationFile declared) {
        if (declared.value() == null || declared.value().isBlank()) {
            throw new EvaluationCorpusException(
                    "Expectation '%s' needs a value".formatted(declared.type()));
        }
        return declared.value().trim();
    }

    private static int count(ExpectationFile declared) {
        try {
            return Integer.parseInt(required(declared));
        } catch (NumberFormatException e) {
            throw new EvaluationCorpusException(
                    "Expectation '%s' needs a whole number, got '%s'".formatted(
                            declared.type(), declared.value()), e);
        }
    }

    /** The suite's on-disk shape, kept separate so Jackson never touches a domain type. */
    record SuiteFile(String revision, List<CaseFile> cases) {
    }

    record CaseFile(
            String name,
            String mode,
            String source,
            String workload,
            List<ExpectationFile> expectations) {
    }

    record ExpectationFile(String type, String value) {
    }
}
