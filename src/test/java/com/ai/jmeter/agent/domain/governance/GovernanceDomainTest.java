package com.ai.jmeter.agent.domain.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Governance")
class GovernanceDomainTest {

    @Nested
    @DisplayName("tenant identity")
    class Tenants {

        @ParameterizedTest
        @ValueSource(strings = {
                "../other-tenant",
                "acme/../globex",
                "acme/secrets",
                "acme tenant",
                "-acme",
                "acme-",
                "ACME_CORP"})
        @DisplayName("refuses anything that is not a slug, because this becomes a path")
        void refusesUnsafeIds(String candidate) {
            // A tenant id is attacker-influenced input in any hosted deployment, and it ends up
            // in a filesystem path. Accepting "../" would make cross-tenant reads a matter of
            // typing one.
            assertThatIllegalArgumentException().isThrownBy(() -> new TenantId(candidate));
        }

        @Test
        @DisplayName("refuses an id longer than a directory name has any business being")
        void refusesOverlongIds() {
            String tooLong = "a".repeat(64);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TenantId(tooLong))
                    .withMessageContaining("is not a valid slug");
            assertThat(new TenantId("a".repeat(63)).value()).hasSize(63);
        }

        @Test
        @DisplayName("refuses a blank id")
        void refusesBlankIds() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TenantId("  "))
                    .withMessageContaining("must not be blank");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TenantId(null))
                    .withMessageContaining("must not be blank");
        }

        @Test
        @DisplayName("canonicalizes case and surrounding space so two spellings are one tenant")
        void canonicalizes() {
            assertThat(new TenantId("  Acme-Corp  ")).isEqualTo(new TenantId("acme-corp"));
            assertThat(new TenantId("acme").toString()).isEqualTo("acme");
        }

        @Test
        @DisplayName("resolves a workspace that stays inside the root it was given")
        void resolvesAContainedWorkspace() {
            Path root = Path.of("/var/lib/agent");

            assertThat(new TenantId("acme").workspaceUnder(root))
                    .isEqualTo(Path.of("/var/lib/agent/acme"))
                    .startsWithRaw(root);
        }

        @Test
        @DisplayName("knows the tenant a run with no tenancy configured belongs to")
        void knowsTheLocalTenant() {
            assertThat(TenantId.local().isLocal()).isTrue();
            assertThat(new TenantId("acme").isLocal()).isFalse();
        }
    }

    @Nested
    @DisplayName("roles and permissions")
    class Roles {

        @Test
        @DisplayName("gives a viewer sight of runs and nothing else")
        void viewersOnlyView() {
            assertThat(Role.VIEWER.grants(Permission.VIEW_RUNS)).isTrue();
            assertThat(Role.VIEWER.grants(Permission.VIEW_HEAL_DIFFS)).isFalse();
            assertThat(Role.VIEWER.grants(Permission.START_RUN)).isFalse();
        }

        @Test
        @DisplayName("lets an approver attest without being able to run")
        void approversDoNotRun() {
            // The separation is the point: the person who attests to a plan is not the person who
            // produced it, which a hierarchy where every higher role subsumes the lower cannot
            // express.
            assertThat(Role.APPROVER.grants(Permission.APPROVE_PLAN)).isTrue();
            assertThat(Role.APPROVER.grants(Permission.START_RUN)).isFalse();
            assertThat(Role.ADMIN.grants(Permission.APPROVE_PLAN)).isFalse();
        }

        @Test
        @DisplayName("lets an operator run and read what the agent changed")
        void operatorsRunAndReview() {
            assertThat(Role.OPERATOR.permissions()).containsExactlyInAnyOrder(
                    Permission.VIEW_RUNS, Permission.VIEW_HEAL_DIFFS, Permission.START_RUN);
        }

        @Test
        @DisplayName("reserves tenant administration for admins")
        void onlyAdminsManageTenants() {
            assertThat(Role.ADMIN.grants(Permission.MANAGE_TENANT)).isTrue();
            assertThat(Role.OPERATOR.grants(Permission.MANAGE_TENANT)).isFalse();
        }
    }

    @Nested
    @DisplayName("a principal")
    class Principals {

        @Test
        @DisplayName("holds every permission any of its roles grants")
        void unionsItsRoles() {
            Principal dana = new Principal(
                    "dana", new TenantId("acme"), Set.of(Role.VIEWER, Role.APPROVER));

            assertThat(dana.can(Permission.VIEW_RUNS)).isTrue();
            assertThat(dana.can(Permission.APPROVE_PLAN)).isTrue();
            assertThat(dana.can(Permission.START_RUN)).isFalse();
        }

        @Test
        @DisplayName("with no roles may do nothing")
        void rolelessPrincipalsCanDoNothing() {
            Principal nobody = new Principal("nobody", new TenantId("acme"), null);

            assertThat(nobody.roles()).isEmpty();
            for (Permission permission : Permission.values()) {
                assertThat(nobody.can(permission)).isFalse();
            }
        }

        @Test
        @DisplayName("refuses an identity or tenant it cannot record")
        void refusesAnUnusableIdentity() {
            TenantId acme = new TenantId("acme");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Principal(" ", acme, Set.of()))
                    .withMessageContaining("must have an identity");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Principal(null, acme, Set.of()))
                    .withMessageContaining("must have an identity");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Principal("dana", null, Set.of()))
                    .withMessageContaining("must belong to a tenant");
        }

        @Test
        @DisplayName("knows which tenant it acts for")
        void knowsItsTenant() {
            Principal dana = new Principal("dana", new TenantId("acme"), Set.of(Role.VIEWER));

            assertThat(dana.belongsTo(new TenantId("acme"))).isTrue();
            assertThat(dana.belongsTo(new TenantId("globex"))).isFalse();
        }

        @Test
        @DisplayName("names the local operator so an audit trail shows nobody vouched for it")
        void localOperatorIsRecognizable() {
            Principal local = Principal.localOperator();

            assertThat(local.id()).isEqualTo("local-operator");
            assertThat(local.tenant().isLocal()).isTrue();
            assertThat(local.describe()).isEqualTo("local-operator@local as [ADMIN]");
            assertThat(local.roleNames()).containsExactly("ADMIN");
        }

        @Test
        @DisplayName("strips surrounding space from an identity before recording it")
        void trimsIdentities() {
            assertThat(new Principal(" dana ", new TenantId("acme"), Set.of()).id())
                    .isEqualTo("dana");
        }
    }

    @Nested
    @DisplayName("a run manifest")
    class Manifests {

        private static RunManifest manifest() {
            Map<String, String> models = new LinkedHashMap<>();
            models.put("GENERATION", "claude-3-5-sonnet");
            models.put("REPAIR", "claude-3-opus");
            return RunManifest.unsigned(
                    "run-1", new TenantId("acme"), "dana", "prompts@v3",
                    models, Map.of("auto_test.jmx", "sha256:abc"),
                    Instant.parse("2025-01-02T03:04:05Z"));
        }

        @Test
        @DisplayName("refuses a manifest that attests to nothing identifiable")
        void refusesAnUnusableManifest() {
            TenantId acme = new TenantId("acme");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RunManifest(" ", acme, "dana", "", "", Map.of(),
                            Map.of(), Instant.EPOCH, ""))
                    .withMessageContaining("must name the run");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RunManifest(null, acme, "dana", "", "", Map.of(),
                            Map.of(), Instant.EPOCH, ""))
                    .withMessageContaining("must name the run");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RunManifest("run-1", null, "dana", "", "", Map.of(),
                            Map.of(), Instant.EPOCH, ""))
                    .withMessageContaining("must name the tenant");
        }

        @Test
        @DisplayName("renders the same bytes however the maps were built")
        void canonicalPayloadIsDeterministic() {
            // A canonical form that depended on map iteration order would produce signatures that
            // fail to verify on a different JVM run.
            Map<String, String> reversed = new LinkedHashMap<>();
            reversed.put("REPAIR", "claude-3-opus");
            reversed.put("GENERATION", "claude-3-5-sonnet");

            RunManifest other = RunManifest.unsigned(
                    "run-1", new TenantId("acme"), "dana", "prompts@v3",
                    reversed, Map.of("auto_test.jmx", "sha256:abc"),
                    Instant.parse("2025-01-02T03:04:05Z"));

            assertThat(other.canonicalPayload()).isEqualTo(manifest().canonicalPayload());
        }

        @Test
        @DisplayName("covers every field an auditor would need to trust")
        void canonicalPayloadCoversEverything() {
            assertThat(manifest().canonicalPayload())
                    .contains("runId=run-1")
                    .contains("tenant=acme")
                    .contains("startedBy=dana")
                    .contains("approvedBy=")
                    .contains("promptRevision=prompts@v3")
                    .contains("claude-3-5-sonnet")
                    .contains("sha256:abc")
                    .contains("recordedAt=2025-01-02T03:04:05Z");
        }

        @Test
        @DisplayName("changes its payload when any attested field changes")
        void anyChangeChangesThePayload() {
            assertThat(manifest().approvedBy("sam").canonicalPayload())
                    .isNotEqualTo(manifest().canonicalPayload());
        }

        @Test
        @DisplayName("discards the signature when an approval is added, so it must be re-signed")
        void approvalInvalidatesTheSignature() {
            RunManifest signed = manifest().signedWith("deadbeef");

            RunManifest approved = signed.approvedBy("sam");

            assertThat(signed.isSigned()).isTrue();
            assertThat(approved.isApproved()).isTrue();
            assertThat(approved.isSigned()).isFalse();
        }

        @Test
        @DisplayName("normalizes a manifest the caller left half-filled")
        void normalizesSparseManifests() {
            RunManifest sparse = new RunManifest(
                    "run-1", new TenantId("acme"), null, null, null, null, null,
                    Instant.EPOCH, null);

            assertThat(sparse.startedBy()).isEmpty();
            assertThat(sparse.approvedBy()).isEmpty();
            assertThat(sparse.promptRevision()).isEmpty();
            assertThat(sparse.modelsByTurn()).isEmpty();
            assertThat(sparse.artifactDigests()).isEmpty();
            assertThat(sparse.signature()).isEmpty();
            assertThat(sparse.isSigned()).isFalse();
            assertThat(sparse.isApproved()).isFalse();
        }

        @Test
        @DisplayName("says plainly when nobody approved it and nothing signed it")
        void describesWhatIsMissing() {
            assertThat(new RunManifest("run-1", new TenantId("acme"), "dana", "", "",
                    Map.of(), Map.of(), Instant.EPOCH, "").describe())
                    .contains("Approved by : nobody")
                    .contains("Prompts     : unrecorded")
                    .contains("Signature   : unsigned");
        }

        @Test
        @DisplayName("shows the approver and signature once it has them")
        void describesWhatItHas() {
            assertThat(manifest().approvedBy("sam").signedWith("deadbeef").describe())
                    .contains("Approved by : sam")
                    .contains("Prompts     : prompts@v3")
                    .contains("Signature   : deadbeef");
        }
    }

    @Test
    @DisplayName("a denial names the missing permission but never the resource")
    void denialsDoNotLeak() {
        // Distinguishing "you may not see this run" from "there is no such run" tells an outsider
        // which run ids exist.
        assertThat(new AccessDeniedException(Permission.VIEW_HEAL_DIFFS))
                .hasMessage("This principal lacks the VIEW_HEAL_DIFFS permission");
    }
}
