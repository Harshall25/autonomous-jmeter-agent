package com.ai.jmeter.agent.domain.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("The self-healing journal")
class HealJournalTest {

    private static HealTurn turn(int attempt, String before, String after) {
        return new HealTurn(attempt, "401 Unauthorized", "The bearer token was never extracted",
                List.of("Add a JSONPath extractor for auth_token"), false,
                PlanDiff.between(before, after));
    }

    @Nested
    @DisplayName("a diff between two plan revisions")
    class Diffing {

        @Test
        @DisplayName("marks what the heal turn added and what it removed")
        void marksAdditionsAndRemovals() {
            PlanDiff diff = PlanDiff.between("""
                    <hashTree>
                      <HTTPSampler name="login"/>
                    </hashTree>""", """
                    <hashTree>
                      <HTTPSampler name="login"/>
                      <JSONPostProcessor name="auth_token"/>
                    </hashTree>""");

            assertThat(diff.addedLines()).isEqualTo(1);
            assertThat(diff.removedLines()).isZero();
            assertThat(diff.render()).contains("+   <JSONPostProcessor name=\"auth_token\"/>");
        }

        @Test
        @DisplayName("shows unchanged lines around a change so a reader can place it")
        void keepsContextAroundChanges() {
            PlanDiff diff = PlanDiff.between("a\nb\nc\nd\ne", "a\nb\nc\nD\ne");

            assertThat(diff.render())
                    .contains("  b")
                    .contains("- d")
                    .contains("+ D")
                    .contains("  e");
        }

        @Test
        @DisplayName("says where in the plan each hunk starts")
        void marksHunkPositions() {
            List<String> before = new ArrayList<>();
            for (int line = 0; line < 40; line++) {
                before.add("line " + line);
            }
            List<String> after = new ArrayList<>(before);
            after.set(2, "changed early");
            after.set(35, "changed late");

            PlanDiff diff = PlanDiff.between(
                    String.join("\n", before), String.join("\n", after));

            // Two separate hunks rather than 40 lines of unchanged plan between them.
            assertThat(diff.unifiedLines().stream().filter(line -> line.startsWith("@@")))
                    .hasSize(2);
            assertThat(diff.addedLines()).isEqualTo(2);
            assertThat(diff.removedLines()).isEqualTo(2);
        }

        @Test
        @DisplayName("reports nothing when the revisions are identical")
        void identicalRevisionsProduceNothing() {
            PlanDiff diff = PlanDiff.between("<plan/>", "<plan/>");

            assertThat(diff.isEmpty()).isTrue();
            assertThat(diff.unifiedLines()).isEmpty();
            assertThat(diff.render()).isEqualTo("(no structural change)");
            assertThat(diff.summarize()).isEqualTo("no change");
        }

        @Test
        @DisplayName("summarizes the size of a change in one line")
        void summarizesChangeSize() {
            assertThat(PlanDiff.between("a\nb", "a\nc\nd").summarize())
                    .isEqualTo("+2/-1 line(s)");
        }

        @Test
        @DisplayName("treats an absent or empty revision as no lines at all")
        void handlesEmptyRevisions() {
            assertThat(PlanDiff.between(null, "a").addedLines()).isEqualTo(1);
            assertThat(PlanDiff.between("", "a\nb").addedLines()).isEqualTo(2);
            assertThat(PlanDiff.between("a", "").removedLines()).isEqualTo(1);
        }

        @Test
        @DisplayName("counts a revision that only deleted lines as a change")
        void deletionOnlyIsNotEmpty() {
            PlanDiff diff = PlanDiff.between("a\nb", "a");

            assertThat(diff.addedLines()).isZero();
            assertThat(diff.isEmpty()).isFalse();
            assertThat(diff.summarize()).isEqualTo("+0/-1 line(s)");
        }

        @Test
        @DisplayName("reports the shape of the change when the old plan is too large to diff")
        void refusesToDiffAHugeBefore() {
            PlanDiff diff = PlanDiff.between(repeatedLines(1501), "small\nplan");

            assertThat(diff.truncated()).isTrue();
            assertThat(diff.render()).contains("1501 line plan replaced by 2 lines");
        }

        @Test
        @DisplayName("reports the shape of the change when the new plan is too large to diff")
        void refusesToDiffAHugeAfter() {
            // Quadratic in the product of the two line counts: a 10,000-line plan would allocate
            // a table two orders of magnitude larger than the plan itself, mid-load-test.
            PlanDiff diff = PlanDiff.between("small\nplan", repeatedLines(2000));

            assertThat(diff.truncated()).isTrue();
            assertThat(diff.addedLines()).isEqualTo(2000);
            assertThat(diff.removedLines()).isEqualTo(2);
        }

        private static String repeatedLines(int count) {
            return "<line/>\n".repeat(count).stripTrailing();
        }
    }

    @Nested
    @DisplayName("a recorded turn")
    class Turns {

        @Test
        @DisplayName("keeps the model's rationale next to the diff it produced")
        void keepsRationaleWithDiff() {
            HealTurn healTurn = turn(1, "<plan/>", "<plan><extractor/></plan>");

            assertThat(healTurn.describe())
                    .contains("Turn after attempt 1")
                    .contains("1 structural edit(s)")
                    .contains("The bearer token was never extracted")
                    .contains("Add a JSONPath extractor for auth_token");
        }

        @Test
        @DisplayName("describes a rewrite as a rewrite, with no edits to list")
        void describesARewrite() {
            HealTurn rewrite = new HealTurn(
                    2, "500 Server Error", "The plan was unsalvageable", List.of(), true,
                    PlanDiff.between("old", "new"));

            assertThat(rewrite.strategy()).isEqualTo("full rewrite");
            assertThat(rewrite.describe()).contains("Edits     : none");
        }

        @Test
        @DisplayName("tolerates a turn the model left half-filled")
        void normalizesMissingFields() {
            HealTurn sparse = new HealTurn(
                    1, null, null, null, false, PlanDiff.between("a", "a"));

            assertThat(sparse.failureSignature()).isEmpty();
            assertThat(sparse.diagnosis()).isEmpty();
            assertThat(sparse.edits()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the journal as a whole")
    class Journal {

        @Test
        @DisplayName("says plainly when nothing was healed")
        void emptyJournalSaysSo() {
            HealJournal journal = HealJournal.empty();

            assertThat(journal.isEmpty()).isTrue();
            assertThat(journal.turnCount()).isZero();
            assertThat(journal.describe())
                    .isEqualTo("The plan passed on the first attempt; nothing was healed.");
        }

        @Test
        @DisplayName("appends a turn without mutating the journal it came from")
        void appendingIsNonDestructive() {
            HealJournal first = HealJournal.empty();
            HealJournal second = first.plus(turn(1, "a", "b"));

            assertThat(first.isEmpty()).isTrue();
            assertThat(second.turnCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("totals how much the loop rewrote across every turn")
        void totalsChurn() {
            HealJournal journal = HealJournal.empty()
                    .plus(turn(1, "a\nb", "a\nc"))
                    .plus(turn(2, "a\nc", "a\nc\nd"));

            assertThat(journal.churn()).isEqualTo("+2/-1 line(s) across 2 turn(s)");
            assertThat(journal.describe())
                    .contains("Self-healing journal (+2/-1 line(s) across 2 turn(s))")
                    .contains("Turn after attempt 1")
                    .contains("Turn after attempt 2");
        }

        @Test
        @DisplayName("treats an absent turn list as an empty journal")
        void nullTurnsAreEmpty() {
            assertThat(new HealJournal(null).isEmpty()).isTrue();
        }
    }
}
