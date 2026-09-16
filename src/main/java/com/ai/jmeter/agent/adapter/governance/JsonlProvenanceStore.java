package com.ai.jmeter.agent.adapter.governance;

import com.ai.jmeter.agent.domain.governance.RunManifest;
import com.ai.jmeter.agent.domain.governance.TenantId;
import com.ai.jmeter.agent.port.ProvenanceException;
import com.ai.jmeter.agent.port.ProvenanceStorePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Driven adapter: append-only provenance on the local filesystem.
 *
 * <p>Append-only is the point, not a limitation of the format. The signature on each manifest
 * makes editing one detectable; refusing to rewrite the file at all makes it awkward as well.
 *
 * <p>Queries by tenant filter here rather than at the caller, so a caller cannot forget to. A
 * store fronting a real database would push the same predicate into the query and, in a hosted
 * deployment, into a row-level policy — but the port's contract is the same either way: this
 * method never returns another tenant's manifest.
 */
public final class JsonlProvenanceStore implements ProvenanceStorePort {

    private static final Logger log = LoggerFactory.getLogger(JsonlProvenanceStore.class);

    private final ObjectMapper objectMapper;
    private final Path storeFile;

    public JsonlProvenanceStore(ObjectMapper objectMapper, Path storeFile) {
        this.objectMapper = objectMapper;
        this.storeFile = storeFile;
    }

    @Override
    public void record(RunManifest manifest) {
        try {
            Files.createDirectories(storeFile.toAbsolutePath().getParent());
            String line = objectMapper.writeValueAsString(StoredManifest.from(manifest));
            Files.writeString(storeFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("Recorded provenance for run {} (tenant {})",
                    manifest.runId(), manifest.tenant());
        } catch (IOException e) {
            throw new ProvenanceException("Unable to record run provenance in " + storeFile, e);
        }
    }

    @Override
    public Optional<RunManifest> find(String runId) {
        return readAll().stream()
                .filter(stored -> stored.runId().equals(runId))
                .map(StoredManifest::toDomain)
                .findFirst();
    }

    @Override
    public List<RunManifest> forTenant(TenantId tenant, int limit) {
        return readAll().stream()
                .filter(stored -> stored.tenant().equals(tenant.value()))
                .map(StoredManifest::toDomain)
                .sorted(Comparator.comparing(RunManifest::recordedAt).reversed())
                .limit(limit)
                .toList();
    }

    private List<StoredManifest> readAll() {
        if (!Files.exists(storeFile)) {
            return List.of();
        }
        List<StoredManifest> manifests = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(storeFile, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    manifests.add(objectMapper.readValue(line, new TypeReference<>() {
                    }));
                }
            }
        } catch (IOException e) {
            // A truncated store degrades to whatever parsed cleanly. Losing the tail of an audit
            // trail is bad; losing all of it because of one bad append is worse.
            log.warn("Provenance at {} is unreadable past entry {}", storeFile, manifests.size(), e);
        }
        return manifests;
    }

    /** The on-disk shape, kept separate so Jackson never touches a domain type. */
    record StoredManifest(
            String runId,
            String tenant,
            String startedBy,
            String approvedBy,
            String promptRevision,
            Map<String, String> models,
            Map<String, String> artifacts,
            String recordedAt,
            String signature) {

        static StoredManifest from(RunManifest manifest) {
            return new StoredManifest(
                    manifest.runId(),
                    manifest.tenant().value(),
                    manifest.startedBy(),
                    manifest.approvedBy(),
                    manifest.promptRevision(),
                    manifest.modelsByTurn(),
                    manifest.artifactDigests(),
                    manifest.recordedAt().toString(),
                    manifest.signature());
        }

        RunManifest toDomain() {
            return new RunManifest(
                    runId, new TenantId(tenant), startedBy, approvedBy, promptRevision,
                    models, artifacts, Instant.parse(recordedAt), signature);
        }
    }
}
