package com.ai.jmeter.agent.domain.journal;

import java.util.ArrayList;
import java.util.List;

/**
 * A line-level diff between two versions of a test plan, rendered as a unified diff.
 *
 * <p>The self-healing loop mutates a test plan without a human in the room. Engineers are right
 * not to trust that, and telling them "the agent healed the plan" is not evidence. A diff is:
 * it turns each heal turn into the same artifact a code review is built on, which is what lets a
 * reviewer sign off on an autonomous change in seconds rather than re-reading the whole plan.
 *
 * @param unifiedLines the diff body, already prefixed with {@code +}, {@code -} or a space, with
 *                     {@code @@} markers between hunks; empty when nothing changed
 * @param addedLines   how many lines the newer version introduced
 * @param removedLines how many lines the older version lost
 * @param truncated    {@code true} when the plans were too large to diff line by line, so only
 *                     the shape of the change is reported
 */
public record PlanDiff(
        List<String> unifiedLines, int addedLines, int removedLines, boolean truncated) {

    /**
     * Above this many lines the quadratic longest-common-subsequence table stops being a
     * reasonable thing to allocate inside a load test. A plan this large has been rewritten
     * wholesale rather than patched, and the line-by-line detail would not be read anyway.
     */
    private static final int MAX_DIFFABLE_LINES = 1500;

    /** Unchanged lines kept either side of a change, as in {@code diff -U3}. */
    private static final int CONTEXT_LINES = 3;

    public PlanDiff {
        unifiedLines = List.copyOf(unifiedLines);
    }

    /**
     * Diffs two plan revisions.
     *
     * @param before the plan as it stood before the heal turn
     * @param after  the plan the heal turn produced
     * @return the change between them
     */
    public static PlanDiff between(String before, String after) {
        List<String> left = splitLines(before);
        List<String> right = splitLines(after);

        if (left.size() > MAX_DIFFABLE_LINES || right.size() > MAX_DIFFABLE_LINES) {
            return new PlanDiff(
                    List.of("(%d line plan replaced by %d lines; too large to diff line by line)"
                            .formatted(left.size(), right.size())),
                    right.size(), left.size(), true);
        }
        return fromEdits(editScript(left, right));
    }

    /** @return {@code true} when the two revisions were identical. */
    public boolean isEmpty() {
        return addedLines == 0 && removedLines == 0;
    }

    /** @return the diff as it would be read in a terminal or a review comment. */
    public String render() {
        if (isEmpty()) {
            return "(no structural change)";
        }
        return String.join(System.lineSeparator(), unifiedLines);
    }

    /** @return a one-line summary, for a log or a list view that has no room for the body. */
    public String summarize() {
        if (isEmpty()) {
            return "no change";
        }
        return "+%d/-%d line(s)".formatted(addedLines, removedLines);
    }

    private static List<String> splitLines(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        return List.of(content.split("\\R", -1));
    }

    /**
     * Walks the longest common subsequence to produce the ordered edit script: which lines were
     * kept, which were dropped and which were introduced.
     */
    private static List<Edit> editScript(List<String> left, List<String> right) {
        int leftLength = left.size();
        int rightLength = right.size();
        int[][] common = new int[leftLength + 1][rightLength + 1];

        for (int i = leftLength - 1; i >= 0; i--) {
            for (int j = rightLength - 1; j >= 0; j--) {
                common[i][j] = left.get(i).equals(right.get(j))
                        ? common[i + 1][j + 1] + 1
                        : Math.max(common[i + 1][j], common[i][j + 1]);
            }
        }

        List<Edit> edits = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < leftLength && j < rightLength) {
            if (left.get(i).equals(right.get(j))) {
                edits.add(new Edit(Kind.KEPT, left.get(i)));
                i++;
                j++;
            } else if (common[i + 1][j] >= common[i][j + 1]) {
                edits.add(new Edit(Kind.REMOVED, left.get(i)));
                i++;
            } else {
                edits.add(new Edit(Kind.ADDED, right.get(j)));
                j++;
            }
        }
        while (i < leftLength) {
            edits.add(new Edit(Kind.REMOVED, left.get(i++)));
        }
        while (j < rightLength) {
            edits.add(new Edit(Kind.ADDED, right.get(j++)));
        }
        return edits;
    }

    /**
     * Renders the edit script as hunks: changed lines with a few lines of context either side, so
     * a reader can see where in the plan the change landed without reading the plan.
     */
    private static PlanDiff fromEdits(List<Edit> edits) {
        boolean[] included = new boolean[edits.size()];
        int added = 0;
        int removed = 0;

        for (int index = 0; index < edits.size(); index++) {
            Kind kind = edits.get(index).kind();
            if (kind == Kind.KEPT) {
                continue;
            }
            if (kind == Kind.ADDED) {
                added++;
            } else {
                removed++;
            }
            int from = Math.max(0, index - CONTEXT_LINES);
            int to = Math.min(edits.size() - 1, index + CONTEXT_LINES);
            for (int context = from; context <= to; context++) {
                included[context] = true;
            }
        }

        List<String> lines = new ArrayList<>();
        boolean inHunk = false;
        for (int index = 0; index < edits.size(); index++) {
            if (!included[index]) {
                inHunk = false;
                continue;
            }
            if (!inHunk) {
                lines.add("@@ line " + (index + 1) + " @@");
                inHunk = true;
            }
            lines.add(edits.get(index).render());
        }
        return new PlanDiff(lines, added, removed, false);
    }

    private enum Kind {
        KEPT, ADDED, REMOVED
    }

    private record Edit(Kind kind, String text) {

        String render() {
            return switch (kind) {
                case KEPT -> "  " + text;
                case ADDED -> "+ " + text;
                case REMOVED -> "- " + text;
            };
        }
    }
}
