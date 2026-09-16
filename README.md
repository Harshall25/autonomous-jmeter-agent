# Autonomous JMeter Performance Testing Agent

An agent that turns a capture of real traffic into a working JMeter test plan, runs it, reads the
failures, and repairs itself until the run is clean — then tells you what it changed and why.

The central invariant: **a plan is never finished because the model says so, only because a real
JMeter run reported a 100% sampler success rate.** Every failing run becomes evidence for the next
turn, and the retry budget is bounded, so a plan the model cannot fix surfaces as a failure rather
than an infinite spend loop.

## Quick start

```bash
export ANTHROPIC_API_KEY=sk-ant-...
export JMETER_HOME=/opt/jmeter/bin

mvn -B package
java -jar target/autonomous-jmeter-agent-1.0.0-SNAPSHOT.jar \
  --mode=API \
  --source=captures/checkout.har \
  --workload=telemetry/access.log
```

The plan lands in `workspace/auto_test.jmx` and its test data in `workspace/test_data.csv`.

| Argument | Meaning |
| --- | --- |
| `--mode` | `API`, `OPENAPI`, `POSTMAN`, `SQL` or `STREAMING` |
| `--source` | the capture or specification to learn from |
| `--workload` | optional production access log; without it the plan is a single-user correctness pass |
| `--evaluate` | run a golden corpus instead of a load test (see below) |

## Architecture

Strict hexagonal (ports and adapters) as a modular monolith.

```
com.ai.jmeter.agent
├── domain/         pure Java. Records, enums, sealed interfaces. Zero framework imports.
├── port/           interfaces only, plus the exceptions they declare.
├── adapter/        every framework-specific thing: Spring AI, DOM, ProcessBuilder, HTTP, files.
├── orchestrator/   the agentic loop. SLF4J is the only third-party import it has.
└── config/         the composition root. The one place ports are bound to adapters.
```

Wiring is explicit rather than annotation-driven, so the core is constructible in a unit test with
nothing but `new`. `HexagonalArchitectureTest` makes the dependency rule a build failure rather than
a convention: the domain may import nothing but the JDK and itself, ports declare only contracts,
and neither the domain nor the orchestrator may reach back into an adapter.

Coverage is gated at **100% line and 100% branch** on `verify`. Where a branch could not be reached,
the unreachable code was removed rather than the bar lowered; where reaching it needed a seam, the
seam was added (`ProcessRunner`, `TransformerFactory`, `ContentDigest`).

## What it does

**Ingestion.** HAR captures, OpenAPI/Swagger documents, Postman collections, slow query logs and
broker topic manifests, each behind its own `TrafficParserPort`.

**Redaction before egress.** Bearer tokens, session cookies, PAN and SSN patterns are detected and
substituted *before* any bytes reach the model, and the withheld values are bound at run time from a
properties file JMeter reads. Under `strict-compliance` a capture carrying regulated material is
refused outright rather than sent in substituted form.

**Structural repair.** The model proposes typed mutations against a DOM-backed plan — add a JSONPath
extractor, bind a CSV column, set a header — rather than regenerating thousands of lines of XML. A
pre-flight validation pass rejects a plan that cannot work before an execution is spent on it.

**Heal memory.** Each `(failure signature → applied mutation → outcome)` is remembered and recalled
into later repair turns, so a repeated failure is a first-attempt success next time.

**Cost governance.** Per-run token budgets, per-turn model routing, and a cost record on every
outcome for chargeback.

**Workload shaping.** Arrival rates, concurrency and endpoint mix inferred from a production access
log, applied as structural edits rather than asked for in the prompt.

**Distributed execution.** `agent.jmeter.distributed` swaps the local process engine for a
Kubernetes engine that fans out across worker pods and merges their JTL shards. Nothing above the
port layer knows which one it got.

**Service virtualization.** Declared dependencies become WireMock stubs with configured latency and
failure rates, turning "the partner API was slow" from a condition you suffer into a variable you
set.

**Analytics.** Per-endpoint baselines with robust (median/MAD) regression detection, W3C traceparent
injection for span correlation, Universal Scalability Law fitting for capacity headroom, and an
LLM root-cause turn that runs only when something actually got slower.

## Reviewing what the agent changed

Every self-healing turn is recorded with the failure it answered, the model's stated rationale, the
edits applied and a unified diff between the two plan revisions — the artifact a code review is
built on. Start the control plane to read them:

```bash
java -jar target/autonomous-jmeter-agent-1.0.0-SNAPSHOT.jar \
  --spring.main.web-application-type=servlet
```

`GET /api/runs` lists runs, `GET /api/runs/{id}` serves every heal turn with its diff, and `/`
renders them. Read-only: an endpoint that could start a load test would be a denial-of-service
primitive wearing a REST interface.

## Gating a merge

```yaml
agent:
  gate:
    enabled: true
    fail-on-regression: true
    max-p95-millis: 1500
```

The gate publishes a percentile-delta table as a pull request comment whether it passes or fails —
a table showing everything held steady is what earns it the trust to block a merge on the day it
does not. A breach exits **2**, distinct from a crash's **1**, so a pipeline can tell "too slow"
from "the agent broke". See [`examples/ci/github-actions.yml`](examples/ci/github-actions.yml).

## Measuring the agent itself

Prompt text is behaviour. A golden corpus scores each revision on first-attempt success rate,
structural correctness, heal cycles and token cost, and names every metric a change made worse:

```bash
java -jar target/autonomous-jmeter-agent-1.0.0-SNAPSHOT.jar \
  --evaluate=examples/corpus/suite.yaml
```

Expectations are structural (`correlates the variable auth_token`, `leaves no reference
unresolved`), not textual — asserting on XML fails the day the model reorders an attribute, and a
suite that fails on style gets rewritten until it asserts nothing.

## Multi-tenancy and provenance

Off by default. Turned on, each tenant's runs execute in their own process and workspace, the
control plane scopes every read to the caller's tenant, roles are checked per permission, and each
run is recorded as an HMAC-signed manifest naming the tenant, the requester, the approver, the
prompt revision, the models and every artifact digest.

```yaml
agent:
  tenancy:
    enabled: true
    tenant: acme
    signing-key: ${AGENT_SIGNING_KEY}   # >= 32 chars, from a secret store
```

Identity comes from headers an authenticating proxy asserts. **This is only safe behind a gateway
that strips those headers from inbound requests and sets them itself** — the agent trusts what it
is told. With no signing key it refuses to sign rather than recording something that proves nothing.

## Configuration

Everything lives under `agent.jmeter.*` (the agent), `agent.gate.*` (the CI policy) and
`agent.tenancy.*` (isolation and provenance). See
[`src/main/resources/application.yml`](src/main/resources/application.yml), which documents each
setting where it is defined.

## Building

```bash
mvn -B clean verify     # tests plus the 100% coverage gate
```

Java 21, Spring Boot 3.5, Spring AI 1.0.
