package com.ai.jmeter.agent.adapter.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.ai.jmeter.agent.domain.cost.AgentTurn;
import com.ai.jmeter.agent.domain.cost.RunCost;
import com.ai.jmeter.agent.domain.cost.TokenBudgetExceededException;
import com.ai.jmeter.agent.domain.cost.TokenUsage;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;

/** Covers the spending ceiling and per-turn routing that make the agent safe to leave running. */
class CostGovernanceTest {

    @Nested
    @DisplayName("BudgetedCostGovernor")
    class Governor {

        @Test
        @DisplayName("accumulates usage across turns")
        void accumulatesUsage() {
            BudgetedCostGovernor governor = new BudgetedCostGovernor(0);
            governor.beginRun();

            governor.recordTurn(AgentTurn.GENERATION, new TokenUsage(100, 400));
            governor.recordTurn(AgentTurn.REPAIR, new TokenUsage(50, 20));

            assertThat(governor.currentCost().totalTokens()).isEqualTo(570);
        }

        @Test
        @DisplayName("keeps a per-turn breakdown for attribution")
        void breaksDownByTurn() {
            BudgetedCostGovernor governor = new BudgetedCostGovernor(0);
            governor.beginRun();

            governor.recordTurn(AgentTurn.REPAIR, new TokenUsage(10, 5));
            governor.recordTurn(AgentTurn.REPAIR, new TokenUsage(20, 5));

            assertThat(governor.currentCost().byTurn())
                    .containsEntry(AgentTurn.REPAIR, new TokenUsage(30, 10));
        }

        @Test
        @DisplayName("stops a run that spends past its ceiling")
        void enforcesBudget() {
            BudgetedCostGovernor governor = new BudgetedCostGovernor(100);
            governor.beginRun();

            assertThatExceptionOfType(TokenBudgetExceededException.class)
                    .isThrownBy(() -> governor.recordTurn(
                            AgentTurn.GENERATION, new TokenUsage(80, 40)))
                    .satisfies(thrown -> assertThat(thrown.cost().totalTokens()).isEqualTo(120))
                    .withMessageContaining("agent.cost.token-budget");
        }

        @Test
        @DisplayName("allows a run that lands exactly on its ceiling")
        void allowsExactBudget() {
            BudgetedCostGovernor governor = new BudgetedCostGovernor(100);
            governor.beginRun();

            governor.recordTurn(AgentTurn.GENERATION, new TokenUsage(60, 40));

            assertThat(governor.currentCost().withinBudget()).isTrue();
        }

        @Test
        @DisplayName("treats a budget of zero as unlimited")
        void zeroBudgetIsUnlimited() {
            BudgetedCostGovernor governor = new BudgetedCostGovernor(0);
            governor.beginRun();

            governor.recordTurn(AgentTurn.GENERATION, new TokenUsage(1_000_000, 1_000_000));

            assertThat(governor.currentCost().withinBudget()).isTrue();
        }

        @Test
        @DisplayName("resets accounting between runs")
        void resetsBetweenRuns() {
            BudgetedCostGovernor governor = new BudgetedCostGovernor(0);
            governor.beginRun();
            governor.recordTurn(AgentTurn.GENERATION, new TokenUsage(100, 100));

            governor.beginRun();

            assertThat(governor.currentCost().totalTokens()).isZero();
        }
    }

    @Nested
    @DisplayName("ModelRouter")
    class Routing {

        @Test
        @DisplayName("leaves every turn on the client default until routing is configured")
        void defaultsToClientModel() {
            ModelRouter router = ModelRouter.usingDefaults();

            assertThat(router.optionsFor(AgentTurn.GENERATION)).isNull();
            assertThat(router.optionsFor(AgentTurn.REPAIR)).isNull();
        }

        @Test
        @DisplayName("pins a configured turn to its own model")
        void routesConfiguredTurn() {
            ModelRouter router = new ModelRouter(Map.of(
                    AgentTurn.GENERATION, "cheap-model",
                    AgentTurn.REPAIR, "frontier-model"));

            ChatOptions generation = router.optionsFor(AgentTurn.GENERATION);
            ChatOptions repair = router.optionsFor(AgentTurn.REPAIR);

            assertThat(generation).isNotNull();
            assertThat(generation.getModel()).isEqualTo("cheap-model");
            assertThat(repair.getModel()).isEqualTo("frontier-model");
        }

        @Test
        @DisplayName("leaves an unconfigured turn alone even when others are routed")
        void leavesUnconfiguredTurnsAlone() {
            ModelRouter router = new ModelRouter(Map.of(AgentTurn.REPAIR, "frontier-model"));

            assertThat(router.optionsFor(AgentTurn.REWRITE)).isNull();
        }
    }

    @Nested
    @DisplayName("cost values")
    class CostValues {

        @Test
        @DisplayName("token usage sums and never goes negative")
        void usageArithmetic() {
            assertThat(new TokenUsage(10, 5).total()).isEqualTo(15);
            assertThat(new TokenUsage(10, 5).plus(new TokenUsage(1, 2)))
                    .isEqualTo(new TokenUsage(11, 7));
            assertThat(new TokenUsage(-5, -1)).isEqualTo(new TokenUsage(0, 0));
            assertThat(TokenUsage.UNKNOWN.total()).isZero();
        }

        @Test
        @DisplayName("an empty run cost reports nothing spent")
        void emptyCost() {
            RunCost cost = RunCost.empty(500);

            assertThat(cost.totalTokens()).isZero();
            assertThat(cost.withinBudget()).isTrue();
            assertThat(cost.describe()).contains("0 token(s)").contains("budget 500");
        }

        @Test
        @DisplayName("an unbudgeted run says so rather than implying a limit")
        void unbudgetedCostDescription() {
            assertThat(RunCost.empty(0).describe()).contains("no budget");
        }
    }
}
