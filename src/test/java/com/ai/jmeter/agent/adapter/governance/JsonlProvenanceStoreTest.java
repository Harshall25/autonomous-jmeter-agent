package com.ai.jmeter.agent.adapter.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.jmeter.agent.domain.governance.RunManifest;
import com.ai.jmeter.agent.domain.governance.TenantId;
import com.ai.jmeter.agent.port.ProvenanceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("JsonlProvenanceStore")
class JsonlProvenanceStoreTest {

    private static final TenantId ACME = new TenantId("acme");
    private static final TenantId GLOBEX = new TenantId("globex");

    @TempDir
    Path workspace;

    private Path storeFile;
    private JsonlProvenanceStore store;

    @BeforeEach
    void setUp() {
        storeFile = workspace.resolve("provenance.jsonl");
        store = new JsonlProvenanceStore(new ObjectMapper(), storeFile);
    }

    private static RunManifest manifest(String runId, TenantId tenant, Instant at) {
        return RunManifest.unsigned(
                runId, tenant, "dana", "prompts@v3",
                Map.of("GENERATION", "claude-3-5-sonnet"),
                Map.of("auto_test.jmx", "sha256:abc"),
                at).signedWith("signature-for-" + runId);
    }

    @Test
    @DisplayName("reads back everything an auditor would need, unaltered")
    void roundTripsAManifest() {
        store.record(manifest("run-1", ACME, Instant.parse("2025-01-02T03:04:05Z")));

        RunManifest stored = store.find("run-1").orElseThrow();

        assertThat(stored.tenant()).isEqualTo(ACME);
        assertThat(stored.startedBy()).isEqualTo("dana");
        assertThat(stored.promptRevision()).isEqualTo("prompts@v3");
        assertThat(stored.modelsByTurn()).containsEntry("GENERATION", "claude-3-5-sonnet");
        assertThat(stored.artifactDigests()).containsEntry("auto_test.jmx", "sha256:abc");
        assertThat(stored.recordedAt()).isEqualTo(Instant.parse("2025-01-02T03:04:05Z"));
        assertThat(stored.signature()).isEqualTo("signature-for-run-1");
    }

    @Test
    @DisplayName("keeps the canonical payload identical across a round trip")
    void roundTripPreservesTheSignedBytes() {
        // If storage changed the payload, every recorded signature would stop verifying.
        RunManifest original = manifest("run-1", ACME, Instant.parse("2025-01-02T03:04:05Z"));
        store.record(original);

        assertThat(store.find("run-1").orElseThrow().canonicalPayload())
                .isEqualTo(original.canonicalPayload());
    }

    @Test
    @DisplayName("never hands one tenant another tenant's provenance")
    void scopesReadsToTheTenant() {
        store.record(manifest("acme-run", ACME, Instant.EPOCH));
        store.record(manifest("globex-run", GLOBEX, Instant.EPOCH));

        assertThat(store.forTenant(ACME, 10)).singleElement()
                .satisfies(found -> assertThat(found.runId()).isEqualTo("acme-run"));
        assertThat(store.forTenant(GLOBEX, 10)).singleElement()
                .satisfies(found -> assertThat(found.runId()).isEqualTo("globex-run"));
    }

    @Test
    @DisplayName("returns a tenant's manifests newest first, within the page asked for")
    void ordersNewestFirst() {
        store.record(manifest("older", ACME, Instant.parse("2025-01-01T00:00:00Z")));
        store.record(manifest("newer", ACME, Instant.parse("2025-03-01T00:00:00Z")));

        assertThat(store.forTenant(ACME, 10).stream().map(RunManifest::runId))
                .containsExactly("newer", "older");
        assertThat(store.forTenant(ACME, 1)).singleElement()
                .satisfies(found -> assertThat(found.runId()).isEqualTo("newer"));
    }

    @Test
    @DisplayName("reports an empty store rather than failing on a first read")
    void missingStoreIsEmpty() {
        assertThat(store.forTenant(ACME, 10)).isEmpty();
        assertThat(store.find("anything")).isEmpty();
    }

    @Test
    @DisplayName("reports no such run rather than inventing one")
    void unknownRunIsEmpty() {
        store.record(manifest("run-1", ACME, Instant.EPOCH));

        assertThat(store.find("run-2")).isEmpty();
    }

    @Test
    @DisplayName("skips blank lines a partial append can leave behind")
    void skipsBlankLines() throws IOException {
        store.record(manifest("run-1", ACME, Instant.EPOCH));
        Files.writeString(storeFile, System.lineSeparator() + "  " + System.lineSeparator(),
                StandardOpenOption.APPEND);

        assertThat(store.forTenant(ACME, 10)).hasSize(1);
    }

    @Test
    @DisplayName("degrades to what parsed cleanly rather than losing the whole trail")
    void survivesACorruptEntry() throws IOException {
        store.record(manifest("run-1", ACME, Instant.EPOCH));
        Files.writeString(storeFile, "{not json" + System.lineSeparator(),
                StandardOpenOption.APPEND);

        assertThat(store.forTenant(ACME, 10)).singleElement()
                .satisfies(found -> assertThat(found.runId()).isEqualTo("run-1"));
    }

    @Test
    @DisplayName("reports a store it cannot write to")
    void reportsAnUnwritableStore() throws IOException {
        Path blocked = workspace.resolve("blocked");
        Files.writeString(blocked, "not a directory");
        JsonlProvenanceStore brittle = new JsonlProvenanceStore(
                new ObjectMapper(), blocked.resolve("nested/provenance.jsonl"));
        RunManifest manifest = manifest("run-1", ACME, Instant.EPOCH);

        assertThatThrownBy(() -> brittle.record(manifest))
                .isInstanceOf(ProvenanceException.class)
                .hasMessageContaining("Unable to record run provenance in");
    }
}
