package com.ai.jmeter.agent.adapter.memory;

import com.ai.jmeter.agent.domain.memory.HealPrecedent;
import com.ai.jmeter.agent.port.HealMemoryPort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Driven adapter: an append-only JSONL store of past repairs, ranked by lexical similarity.
 *
 * <p>Similarity is Jaccard overlap over the tokens of a failure signature. That is a deliberate
 * choice of floor rather than ceiling: it needs no embedding model, no external service and no
 * network call, so memory works out of the box on a laptop and in an air-gapped build. Because
 * retrieval sits behind {@link HealMemoryPort}, swapping it for pgvector or OpenSearch later is
 * an adapter change with no reach into the agentic loop.
 *
 * <p>Signatures are short, structured strings like {@code SAMPLE_FAILURE|401:login}, so lexical
 * overlap tracks semantic similarity closely here in a way it would not for free text.
 */
public final class JsonlHealMemoryAdapter implements HealMemoryPort {

    private static final Logger log = LoggerFactory.getLogger(JsonlHealMemoryAdapter.class);

    /** Below this overlap a precedent is more likely to mislead the model than to help it. */
    private static final double MINIMUM_SIMILARITY = 0.34;

    private final ObjectMapper objectMapper;
    private final Path storeFile;

    public JsonlHealMemoryAdapter(ObjectMapper objectMapper, Path storeFile) {
        this.objectMapper = objectMapper;
        this.storeFile = storeFile;
    }

    @Override
    public List<HealPrecedent> recall(String signature, int limit) {
        Set<String> wanted = tokenize(signature);
        List<HealPrecedent> ranked = readAll().stream()
                .map(entry -> new ScoredPrecedent(
                        entry.toDomain(), similarity(wanted, tokenize(entry.signature()))))
                .filter(scored -> scored.score() >= MINIMUM_SIMILARITY)
                .sorted(Comparator.comparingDouble(ScoredPrecedent::score).reversed())
                .limit(limit)
                .map(ScoredPrecedent::precedent)
                .toList();

        if (!ranked.isEmpty()) {
            log.info("Recalled {} precedent(s) for failure signature {}", ranked.size(), signature);
        }
        return ranked;
    }

    @Override
    public void remember(HealPrecedent precedent) {
        try {
            Path parent = storeFile.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            String line = objectMapper.writeValueAsString(StoredPrecedent.from(precedent));
            Files.writeString(storeFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.debug("Remembered repair for signature {}", precedent.signature());
        } catch (IOException e) {
            // Memory is an optimization. Losing a write must never fail a run that is otherwise
            // succeeding, so this degrades to a cold-start agent rather than an aborted one.
            log.warn("Could not record repair precedent in {}", storeFile, e);
        }
    }

    private List<StoredPrecedent> readAll() {
        if (!Files.exists(storeFile)) {
            return List.of();
        }
        List<StoredPrecedent> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(storeFile, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    entries.add(objectMapper.readValue(line, new TypeReference<>() {
                    }));
                }
            }
        } catch (IOException e) {
            // A truncated or hand-edited store degrades to whatever parsed cleanly before it.
            log.warn("Heal memory at {} is unreadable past entry {}", storeFile, entries.size(), e);
        }
        return entries;
    }

    /** @return Jaccard overlap, or zero when either side has nothing to compare. */
    private static double similarity(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        long shared = left.stream().filter(right::contains).count();
        long union = left.size() + right.size() - shared;
        return (double) shared / union;
    }

    private static Set<String> tokenize(String signature) {
        return java.util.Arrays.stream(signature.toLowerCase(Locale.ROOT).split("[|,:\\s]+"))
                .filter(token -> !token.isBlank())
                .collect(Collectors.toSet());
    }

    private record ScoredPrecedent(HealPrecedent precedent, double score) {
    }

    /**
     * The on-disk shape. Kept separate from the domain record so persistence format and domain
     * model can evolve independently, and so Jackson never touches a domain type.
     */
    record StoredPrecedent(
            String signature, String diagnosis, List<String> appliedEdits, boolean worked) {

        static StoredPrecedent from(HealPrecedent precedent) {
            return new StoredPrecedent(
                    precedent.signature(), precedent.diagnosis(),
                    precedent.appliedEdits(), precedent.worked());
        }

        HealPrecedent toDomain() {
            return new HealPrecedent(signature, diagnosis, appliedEdits, worked);
        }
    }
}
