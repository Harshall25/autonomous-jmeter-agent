# Usage Guide

## What this actually does

You give it a recording of real traffic. It writes a JMeter test plan, runs it,
reads the failures, fixes the plan, and repeats until a real JMeter run reports
100% sampler success — then tells you what it changed and why.

The invariant worth internalising: **a plan is never finished because the model
says so, only because JMeter actually ran it clean.** The model's output is a
hypothesis; the JMeter run is the test.

---

## Your first run in five minutes

### 1. Get a capture

Open your app in Chrome → DevTools (F12) → Network tab → do the thing you want
to load-test (log in, add to cart, check out) → right-click the request list →
**Save all as HAR with content**.

> "with content" matters — response bodies are where the agent finds the tokens
> and ids it needs to correlate.

### 2. Run it

```bash
set -a; . ./.env; set +a        # java -jar does NOT read .env by itself
java -jar target/autonomous-jmeter-agent-1.0.0-SNAPSHOT.jar \
  --mode=API --source=captures/checkout.har
```

### 3. Read what happened

```
Redacted 2 secret(s) before model egress: {EMAIL=1, CREDENTIAL=1}
Initial plan generated. Variables identified: [auth_token, user]
Attempt 1/3 failed: status=SAMPLE_FAILURE failedSamples=1
Applied 1 structural edit(s):
  Add a JSONPath extractor for auth_token on 'login'
Attempt 2/3 passed with 4 samples and no failures

Agentic run complete after 2 attempt(s).
  Test plan : workspace/auto_test.jmx
  Test data : workspace/test_data.csv
  Samples   : 4, all passing
  Healing   : +3/-1 line(s) across 1 turn(s)
```

That middle section is the whole product. The first attempt 401'd because the
plan sent a hard-coded token; the agent noticed, added an extractor, and the
retry passed. Nobody intervened.

### 4. Open the plan

`workspace/auto_test.jmx` opens in the JMeter GUI like any other plan. It is
yours — commit it, edit it, keep it.

---

## The five ingestion modes

| Mode | Source | Use when |
| --- | --- | --- |
| `API` | HAR capture | You have real traffic. **Best results** — real payloads, real sequencing. |
| `OPENAPI` | `openapi.yaml` / `.json` | The API exists on paper but not yet in production |
| `POSTMAN` | Collection export | Your team already maintains one |
| `SQL` | Slow query log | You are testing the database directly |
| `STREAMING` | Topic manifest | Kafka / JMS / MQTT workloads |

```bash
--mode=OPENAPI --source=openapi.yaml
--mode=SQL     --source=slow-queries.log      # needs a JDBC driver in <jmeter>/lib/
```

---

## Making it a load test, not a smoke test

By default the plan runs **one user, one loop** — a correctness check that proves
the plan works, not a load test.

To make it reproduce production, give it an access log:

```bash
--mode=API --source=checkout.har --workload=telemetry/access.log
```

The agent infers arrival rate, concurrency, ramp-up and endpoint mix, and applies
them as structural edits to the plan.

This matters more than it sounds. A plan that replays your requests in the wrong
ratio and cadence produces confident numbers that predict nothing. Workload shape
is the single biggest determinant of whether a load test tells you the truth.

Supported: Common Log Format and Combined Log Format (nginx/Apache default).

---

## Reading what the agent changed

Start the control plane:

```bash
java -jar agent.jar --spring.main.web-application-type=servlet
# http://localhost:8080
```

You get every run, and for each self-healing turn: the failure it answered, the
model's stated rationale, and a colour-coded diff of what actually changed in the
plan.

**Read the rationale against the diff.** A rationale that does not match its diff
is the failure mode worth catching — it means the model explained one thing and
did another. That is exactly why they are shown side by side.

The API, if you want to script it:

```bash
curl localhost:8080/api/runs              # recent runs
curl localhost:8080/api/runs/{runId}      # one run, with every heal turn and diff
```

Read-only by design. Runs start from the CLI or a pipeline, where the capture and
the workspace live.

---

## Gating a pull request

```bash
java -jar agent.jar --mode=OPENAPI --source=openapi.yaml \
  --agent.gate.enabled=true \
  --agent.gate.fail-on-regression=true \
  --agent.gate.max-p95-millis=1500
```

Posts a table like this on the PR:

| Sampler | p50 | p95 | p99 | baseline p95 | change |
| --- | ---: | ---: | ---: | ---: | ---: |
| checkout | 400ms | 900ms | 1200ms | 300ms | ⚠️ +200% |
| search | 38ms | 60ms | 90ms | 58ms | +3% |

Exit `0` clean, `2` gate blocked, `1` the agent broke. The table is posted whether
it passes or fails — a table showing everything held steady is what earns the gate
the trust to block a merge on the day it does not.

**Policy knobs:**

| Setting | Default | Meaning |
| --- | --- | --- |
| `fail-on-regression` | `true` | Block when a sampler is unusually slower than its own baseline |
| `fail-on-healing` | `false` | Block when the agent had to repair the plan (a healed plan may test something other than what changed) |
| `max-p95-millis` | `0` (off) | Absolute ceiling, regardless of history |
| `max-failure-rate-percent` | `0` | Share of samples allowed to fail |

Regression detection is statistical, not a fixed threshold: it uses a median and
a robust deviation across `history-depth` past runs, and requires both
statistical significance *and* a material change. That is what stops it firing on
noise — and it stays silent until `minimum-baseline-runs` (default 5) have
accumulated.

---

## Measuring the agent itself

Prompt text is behaviour. Editing a system prompt or changing the model can halve
the agent's first-attempt success rate with every unit test still passing.

```bash
java -jar agent.jar --evaluate=examples/corpus/suite.yaml
```

```
Evaluation of prompts@v3 over 3 case(s)
  First-attempt success : 67%
  Fully correct         : 67%
  Mean heal cycles      : 0.67
  Mean token cost       : 4200
```

A suite is YAML next to the captures it names:

```yaml
revision: "prompts@v3 / haiku-4.5"
cases:
  - name: checkout-har
    mode: API
    source: captures/checkout.har
    expectations:
      - {type: sampler,  value: login}
      - {type: variable, value: auth_token}
      - {type: noUnresolvedVariables}
```

Expectations are structural, never textual. `noUnresolvedVariables` is the one
that earns its keep: a plan missing a correlation runs green and comes back 401,
with nothing in the XML to say why.

Run it before shipping a prompt change and after changing the model. Compare the
four numbers.

---

## What lands in the workspace

| File | What it is |
| --- | --- |
| `auto_test.jmx` | The plan that passed. Yours to keep. |
| `test_data.csv` | Parameterised test data |
| `secrets.properties` | **Unredacted credentials.** JMeter binds these at run time. Never commit or archive. |
| `results.jtl` | Raw JMeter results |
| `run-history.jsonl` | Baselines. Delete it and every baseline resets. |
| `heal-memory.jsonl` | Past repairs. Delete it and the agent re-derives fixes it already knew. |
| `run-ledger.jsonl` | What the control plane reads |
| `provenance.jsonl` | Signed audit trail (tenancy mode) |
| `performance-report.md` | The PR comment |

---

## Troubleshooting

**"Every run says `401 invalid x-api-key`."** `java -jar` does not read `.env`.
Run `set -a; . ./.env; set +a` first.

**"It fails instantly with `400 invalid_request_error`."** You set
`ANTHROPIC_MODEL` to a Claude 5 model. Spring AI 1.0.0 sends `temperature`, which
those models reject. Use `claude-haiku-4-5`; see
[DEPLOYMENT.md § Known limitations](DEPLOYMENT.md#known-limitations).

**"`SelfHealingFailedException` after 3 attempts."** The agent gave up honestly
rather than claiming success. Read `workspace/results.jtl` — usually the system
under test is down, the capture is stale, or an endpoint needs data the capture
never showed. Raise `agent.jmeter.max-retries` only after you have read the
failures; more attempts on an unfixable plan just cost more tokens.

**"It generated a plan but every sampler 401s."** Check the heal diffs in the
control plane. If the agent never added an extractor, the capture probably lacks
the login *response body* — re-export the HAR **with content**.

**"The numbers look nothing like production."** You are running a smoke test. Add
`--workload=<access log>`.

**"SQL mode fails at connection time."** No JDBC driver. Drop the jar in
`<jmeter>/lib/`. The agent warns about this at startup and cannot fix it from
inside the plan.

**"My first run reported no regression even though it was slow."** The first run
establishes a baseline; there is nothing to compare against. Detection starts
after `minimum-baseline-runs` (default 5).
