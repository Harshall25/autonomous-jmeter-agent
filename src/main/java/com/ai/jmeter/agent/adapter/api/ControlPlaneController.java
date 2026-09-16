package com.ai.jmeter.agent.adapter.api;

import com.ai.jmeter.agent.port.RunLedgerPort;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Driving adapter: the control plane's read API over the run ledger.
 *
 * <p>The agent mutates test plans on its own. That is only acceptable if what it did is
 * inspectable afterwards by someone who was not watching, which a log line scrolled past in a
 * pipeline is not. This exposes each run and, for each self-healing turn, the model's stated
 * rationale next to the diff it actually produced.
 *
 * <p>Read-only on purpose. Runs are started by the CLI or a pipeline, where the source capture
 * and the workspace live; an endpoint that could start a load test would be a denial-of-service
 * primitive wearing a REST interface.
 */
@RestController
@RequestMapping("/api/runs")
public class ControlPlaneController {

    /** Keeps a careless {@code ?limit=} from trying to read an entire ledger into memory. */
    private static final int MAX_PAGE_SIZE = 200;

    private final RunLedgerPort runLedger;

    public ControlPlaneController(RunLedgerPort runLedger) {
        this.runLedger = runLedger;
    }

    /**
     * @param limit how many runs to return, clamped to a sane page
     * @return the most recent runs, newest first, without their heal turns
     */
    @GetMapping
    public List<RunView> recentRuns(@RequestParam(defaultValue = "25") int limit) {
        return runLedger.recent(Math.clamp(limit, 1, MAX_PAGE_SIZE)).stream()
                .map(RunView::summaryOf)
                .toList();
    }

    /**
     * @param runId the run to inspect
     * @return the run with every heal turn and its diff, or 404 when the ledger has no such run
     */
    @GetMapping("/{runId}")
    public ResponseEntity<RunView> run(@PathVariable String runId) {
        return runLedger.find(runId)
                .map(RunView::detailOf)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
