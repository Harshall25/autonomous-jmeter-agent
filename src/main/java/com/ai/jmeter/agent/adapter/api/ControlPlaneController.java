package com.ai.jmeter.agent.adapter.api;

import com.ai.jmeter.agent.domain.governance.AccessDeniedException;
import com.ai.jmeter.agent.domain.governance.Permission;
import com.ai.jmeter.agent.domain.governance.Principal;
import com.ai.jmeter.agent.port.RunLedgerPort;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
 * <p>Every read is scoped to the caller's tenant and checked against their permissions. Listing
 * runs and inspecting what the agent changed are separate permissions, because an organization
 * that lets auditors see that runs happened does not necessarily let them read the plans, which
 * carry endpoint names and payload shapes.
 *
 * <p>Read-only on purpose. Runs are started by the CLI or a pipeline, where the source capture
 * and the workspace live; an endpoint that could start a load test would be a denial-of-service
 * primitive wearing a REST interface.
 *
 * <p>The one bean Spring constructs by scanning rather than from the composition root. Spring
 * Framework 6.2 detects an MVC handler by {@code @Controller} on the bean's type, so a
 * hand-registered bean carrying only {@code @RequestMapping} is built, injected and then never
 * routed to — every endpoint answers 404 while the context looks perfectly healthy. Its
 * collaborators are still the ports the root bound; the condition keeps it out of a CLI run,
 * where nothing should open a port.
 */
@RestController
@RequestMapping("/api/runs")
@ConditionalOnWebApplication
public class ControlPlaneController {

    /** Keeps a careless {@code ?limit=} from trying to read an entire ledger into memory. */
    private static final int MAX_PAGE_SIZE = 200;

    private final RunLedgerPort runLedger;
    private final PrincipalResolver principals;

    public ControlPlaneController(RunLedgerPort runLedger, PrincipalResolver principals) {
        this.runLedger = runLedger;
        this.principals = principals;
    }

    /**
     * @param limit how many runs to return, clamped to a sane page
     * @return the caller's tenant's most recent runs, newest first, without their heal turns
     */
    @GetMapping
    public List<RunView> recentRuns(
            HttpServletRequest request, @RequestParam(defaultValue = "25") int limit) {
        Principal principal = authorize(request, Permission.VIEW_RUNS);

        return runLedger.recent(principal.tenant(), Math.clamp(limit, 1, MAX_PAGE_SIZE)).stream()
                .map(RunView::summaryOf)
                .toList();
    }

    /**
     * @param runId the run to inspect
     * @return the run with every heal turn and its diff, or 404 when this tenant has no such run
     */
    @GetMapping("/{runId}")
    public ResponseEntity<RunView> run(HttpServletRequest request, @PathVariable String runId) {
        Principal principal = authorize(request, Permission.VIEW_HEAL_DIFFS);

        return runLedger.find(principal.tenant(), runId)
                .map(RunView::detailOf)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Answers a denial with 403 and nothing else.
     *
     * <p>No body: the exception's message names the missing permission, which is useful in a log
     * and is exactly what an outsider probing the API should not be handed.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Void> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    private Principal authorize(HttpServletRequest request, Permission required) {
        Principal principal = principals.resolve(request);
        if (!principal.can(required)) {
            throw new AccessDeniedException(required);
        }
        return principal;
    }
}
