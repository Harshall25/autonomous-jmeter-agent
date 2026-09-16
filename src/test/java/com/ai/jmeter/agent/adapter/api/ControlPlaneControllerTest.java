package com.ai.jmeter.agent.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.controlplane.RunLedgerEntry;
import com.ai.jmeter.agent.domain.governance.AccessDeniedException;
import com.ai.jmeter.agent.domain.governance.Principal;
import com.ai.jmeter.agent.domain.governance.Role;
import com.ai.jmeter.agent.domain.governance.TenantId;
import com.ai.jmeter.agent.domain.journal.HealJournal;
import com.ai.jmeter.agent.domain.journal.HealTurn;
import com.ai.jmeter.agent.domain.journal.PlanDiff;
import com.ai.jmeter.agent.port.RunLedgerPort;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ControlPlaneController")
class ControlPlaneControllerTest {

    private static final TenantId ACME = new TenantId("acme");

    @Mock
    private RunLedgerPort runLedger;

    @Mock
    private HttpServletRequest request;

    private ControlPlaneController controller;

    @BeforeEach
    void setUp() {
        asPrincipal(new Principal("dana", ACME, Set.of(Role.OPERATOR)));
    }

    private void asPrincipal(Principal principal) {
        controller = new ControlPlaneController(runLedger, httpRequest -> principal);
    }

    private static RunLedgerEntry healedRun() {
        HealJournal journal = HealJournal.empty().plus(new HealTurn(
                1, "401 Unauthorized", "The bearer token was never extracted",
                List.of("Add a JSONPath extractor for auth_token"), false,
                PlanDiff.between("<plan/>", "<plan>\n<extractor/>\n</plan>")));
        return new RunLedgerEntry(
                "run-1", ACME, Instant.parse("2025-01-02T03:04:05Z"), ExecutionMode.API, 2, 120,
                "plan-a", 4200, "Correlated the bearer token from /login", journal);
    }

    @Nested
    @DisplayName("listing runs")
    class Listing {

        @Test
        @DisplayName("serves the headline of each run without its diffs")
        void listsWithoutDiffs() {
            when(runLedger.recent(any(), anyInt())).thenReturn(List.of(healedRun()));

            List<RunView> runs = controller.recentRuns(request, 25);

            assertThat(runs).singleElement().satisfies(run -> {
                assertThat(run.runId()).isEqualTo("run-1");
                assertThat(run.mode()).isEqualTo("API");
                assertThat(run.attempts()).isEqualTo(2);
                assertThat(run.healedFirstTime()).isFalse();
                assertThat(run.recordedAt()).isEqualTo("2025-01-02T03:04:05Z");
                assertThat(run.churn()).isEqualTo("+3/-1 line(s) across 1 turn(s)");
                // The list view is a menu, not a payload: a hundred runs of diffs is megabytes.
                assertThat(run.heals()).isEmpty();
            });
        }

        @Test
        @DisplayName("reads only the caller's own tenant")
        void scopesToTheCallersTenant() {
            when(runLedger.recent(any(), anyInt())).thenReturn(List.of());

            controller.recentRuns(request, 25);

            verify(runLedger).recent(eq(ACME), anyInt());
        }

        @Test
        @DisplayName("refuses a caller with no right to see that runs happened")
        void deniesWithoutViewPermission() {
            asPrincipal(new Principal("nobody", ACME, Set.of()));

            assertThatThrownBy(() -> controller.recentRuns(request, 25))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("VIEW_RUNS");
            verifyNoInteractions(runLedger);
        }

        @Test
        @DisplayName("refuses to page an entire ledger into memory")
        void clampsThePageSize() {
            when(runLedger.recent(any(), anyInt())).thenReturn(List.of());

            controller.recentRuns(request, 100_000);

            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(runLedger).recent(any(), limit.capture());
            assertThat(limit.getValue()).isEqualTo(200);
        }

        @Test
        @DisplayName("treats a nonsensical page size as one run")
        void clampsANonsensicalPageSize() {
            when(runLedger.recent(any(), anyInt())).thenReturn(List.of());

            controller.recentRuns(request, -5);

            verify(runLedger).recent(ACME, 1);
        }
    }

    @Nested
    @DisplayName("inspecting one run")
    class Detail {

        @Test
        @DisplayName("serves each heal turn with the rationale and the diff side by side")
        void servesHealTurns() {
            when(runLedger.find(ACME, "run-1")).thenReturn(Optional.of(healedRun()));

            ResponseEntity<RunView> response = controller.run(request, "run-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().rationale()).contains("Correlated the bearer token");
            assertThat(response.getBody().heals()).singleElement().satisfies(turn -> {
                assertThat(turn.attempt()).isEqualTo(1);
                assertThat(turn.strategy()).isEqualTo("1 structural edit(s)");
                assertThat(turn.diagnosis()).contains("bearer token");
                assertThat(turn.failureSignature()).isEqualTo("401 Unauthorized");
                assertThat(turn.edits()).hasSize(1);
                assertThat(turn.addedLines()).isEqualTo(3);
                assertThat(turn.removedLines()).isEqualTo(1);
                assertThat(turn.truncated()).isFalse();
                assertThat(turn.diff()).isNotEmpty();
            });
        }

        @Test
        @DisplayName("answers 404 for a run the caller's tenant has never seen")
        void unknownRunIsNotFound() {
            when(runLedger.find(any(), anyString())).thenReturn(Optional.empty());

            ResponseEntity<RunView> response = controller.run(request, "missing");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNull();
        }

        @Test
        @DisplayName("separates seeing that a run happened from reading what the agent changed")
        void deniesWithoutDiffPermission() {
            // A plan carries endpoint names and payload shapes; an organization may let auditors
            // see runs without letting them read those.
            asPrincipal(new Principal("auditor", ACME, Set.of(Role.VIEWER)));

            assertThatThrownBy(() -> controller.run(request, "run-1"))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("VIEW_HEAL_DIFFS");
            verifyNoInteractions(runLedger);
        }
    }

    @Test
    @DisplayName("answers a denial with 403 and no body to probe")
    void deniedIsForbiddenAndSilent() {
        ResponseEntity<Void> response = controller.denied();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNull();
    }
}
