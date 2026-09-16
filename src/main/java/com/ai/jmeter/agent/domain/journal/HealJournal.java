package com.ai.jmeter.agent.domain.journal;

import java.util.ArrayList;
import java.util.List;

/**
 * The ordered record of every heal turn a run took to reach a passing plan.
 *
 * <p>Immutable, and appended to by producing a new journal, so a partially built record can never
 * be observed mid-run or mutated after the outcome has been handed out.
 *
 * @param turns the heal turns in the order they happened; empty when the plan passed first time
 */
public record HealJournal(List<HealTurn> turns) {

    public HealJournal {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    /** @return a journal for a run that has not healed anything yet. */
    public static HealJournal empty() {
        return new HealJournal(List.of());
    }

    /**
     * @param turn the heal turn that just completed
     * @return a new journal with the turn appended
     */
    public HealJournal plus(HealTurn turn) {
        List<HealTurn> appended = new ArrayList<>(turns);
        appended.add(turn);
        return new HealJournal(appended);
    }

    public boolean isEmpty() {
        return turns.isEmpty();
    }

    public int turnCount() {
        return turns.size();
    }

    /** @return the total lines the loop added and removed across every turn. */
    public String churn() {
        int added = turns.stream().mapToInt(turn -> turn.diff().addedLines()).sum();
        int removed = turns.stream().mapToInt(turn -> turn.diff().removedLines()).sum();
        return "+%d/-%d line(s) across %d turn(s)".formatted(added, removed, turnCount());
    }

    public String describe() {
        if (isEmpty()) {
            return "The plan passed on the first attempt; nothing was healed.";
        }
        String separator = System.lineSeparator() + System.lineSeparator();
        return "Self-healing journal (%s):%s%s".formatted(
                churn(),
                separator,
                String.join(separator, turns.stream().map(HealTurn::describe).toList()));
    }
}
