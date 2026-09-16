# Deployment Guide

## 1. What state is this in?

Be clear about what has been proven and what has not, because it changes how you
should roll this out.

### Verified working

| Capability | How it was verified |
| --- | --- |
| Build and test suite | `mvn clean verify` — 780 tests, 0 failures, 100% line + branch coverage gate passes |
| Packaged jar boots | `java -jar …` starts in ~2.2s and exits 0 with a usage banner, no API key needed |
| HAR ingestion | Real HAR parsed, endpoints extracted |
| Secret redaction | Live run on a HAR containing a bearer token and an email: both detected, stripped before egress, written to `workspace/secrets.properties` for JMeter to bind at run time |
| Control plane HTTP | `GET /api/runs` → `200 []`, unknown run → `404`, UI → `200`, served by a real Tomcat |
| Failure handling | Invalid API key produces a clear `401 invalid x-api-key` and exit code 1 — no partial artifacts |

### NOT verified end-to-end

| Gap | Why | What to do about it |
| --- | --- | --- |
| A real model call | No valid Anthropic key was available during development | Do a staging run against a throwaway capture before trusting it |
| A real JMeter execution | JMeter was not installed in the build environment | Same — the first run in your environment is the real test |
| Kubernetes distributed mode | `kubectl` fan-out is covered by tests with a stubbed process runner, never against a cluster | Treat as beta; run locally first |
| Everything downstream of the model call | Proven against mocked ports, not live | The self-healing loop, gate and provenance are logically exercised but have not met a real 401 from a real SUT |

**Recommended rollout:** single-tenant CLI on one machine → add the CI gate in
report-only mode → turn gating on → add tenancy and provenance if you need them.
Do not start with distributed mode.

### Known limitations

**You cannot use Claude Opus 5 or Sonnet 5 yet.** Spring AI 1.0.0's
auto-configuration unconditionally sets `temperature` (its own default, 0.8), and
its request DTO only omits *null* fields — so `temperature` is always on the
wire. Claude 5 models reject that parameter with HTTP 400. Selecting one makes
every run fail with `400 invalid_request_error`.

The default is therefore `claude-haiku-4-5`, which still accepts sampling
parameters. To unblock the larger models, either:

1. Upgrade `spring-ai.version` in `pom.xml` to a release that omits sampling
   parameters for Claude 5, then set `ANTHROPIC_MODEL=claude-opus-5` and delete
   the `temperature` line from `application.yml`; or
2. Register your own `AnthropicChatModel` bean whose `defaultOptions` leave
   `temperature` null — the auto-configured one backs off — and do the same.

Either way, verify it before relying on it: point `spring.ai.anthropic.base-url`
at a local listener and confirm `temperature` is absent from the request body.

**Other limits:** the control plane is read-only and trusts identity headers
(§6); heal memory, run history, the ledger and provenance are JSONL files, fine
for a single agent process but not for concurrent writers; there is no built-in
metrics endpoint.

---

## 2. Where should this run?

The agent is a **batch job**, not a service. It starts, produces a plan, runs it,
writes artifacts, and exits. Deploy it wherever you run jobs.

| Option | Good for | Notes |
| --- | --- | --- |
| **Laptop / workstation** | Evaluating it, building your first plan | Simplest. Start here. |
| **CI runner** (GitHub Actions, GitLab, Jenkins) | The gate — running on every PR | The primary production use. See §5. |
| **A VM or container host** on the same network as the system under test | Scheduled load tests, bigger runs | Network proximity matters — you are measuring latency |
| **Kubernetes Job + CronJob** | Scheduled runs at scale | Use the container from §4 |
| **Kubernetes with `agent.jmeter.distributed=true`** | Load beyond one JVM | Beta. Needs `kubectl` in the image and RBAC to create the CRD |

**Where NOT to run it:** anywhere sharing a host with the system under test — a
load generator competing for the same CPU produces numbers that measure your test
rig, not your service. And not on a box that cannot reach `api.anthropic.com`.

### Sizing

| Resource | Minimum | Recommended | Why |
| --- | --- | --- | --- |
| CPU | 2 cores | 4+ | JMeter is the consumer; the agent itself is idle waiting on the model |
| RAM | 2 GB | 4–8 GB | Default JMeter heap is 1 GB; raise for high thread counts |
| Disk | 1 GB | 10 GB+ | `.jtl` files grow fast — a million samples is hundreds of MB |
| Network | Outbound HTTPS to `api.anthropic.com`, plus reach to the system under test | | |

Java 21+ is required (the code uses records, sealed interfaces and pattern-matching
switch).

---

## 3. Bare-metal / VM install

```bash
# 1. Java 21
java -version    # must be 21 or newer

# 2. JMeter
curl -fsSL -o jmeter.tgz \
  https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-5.6.3.tgz
sudo tar -xzf jmeter.tgz -C /opt
sudo mv /opt/apache-jmeter-5.6.3 /opt/apache-jmeter

# 3. JDBC driver — only if you will use SQL mode.
#    Without it every JDBC sampler fails at connection time, and the agent
#    cannot fix that from inside the test plan. It warns you at startup.
# sudo cp mysql-connector-j-8.4.0.jar /opt/apache-jmeter/lib/

# 4. The agent
git clone <your-fork> && cd autonomous-jmeter-agent
mvn -B clean verify          # runs the tests and the coverage gate
# -> target/autonomous-jmeter-agent-1.0.0-SNAPSHOT.jar

# 5. Configuration
cp .env.example .env && $EDITOR .env

# 6. Run. NOTE: `java -jar` does not read .env — source it first.
set -a; . ./.env; set +a
java -jar target/autonomous-jmeter-agent-1.0.0-SNAPSHOT.jar \
  --mode=API --source=captures/checkout.har
```

### As a systemd unit (scheduled runs)

```ini
# /etc/systemd/system/jmeter-agent@.service
[Unit]
Description=Autonomous JMeter Agent (%i)
After=network-online.target

[Service]
Type=oneshot
User=jmeter-agent
WorkingDirectory=/var/lib/jmeter-agent
EnvironmentFile=/etc/jmeter-agent/%i.env
ExecStart=/usr/bin/java -jar /opt/jmeter-agent/agent.jar \
          --mode=API --source=/var/lib/jmeter-agent/captures/%i.har
# The agent writes only here.
ReadWritePaths=/var/lib/jmeter-agent
ProtectSystem=strict
PrivateTmp=true
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
```

Pair with a `systemd` timer, or call it from cron. Keep the key in the
`EnvironmentFile` with `chmod 600`, owned by the service user — not on the
`ExecStart` line, where it would be visible in `ps`.

---

## 4. Docker

```bash
cp .env.example .env && $EDITOR .env
mkdir -p captures && cp ~/checkout.har captures/

# One-off run
docker compose run --rm agent --mode=API --source=/captures/checkout.har

# Artifacts live in the named volume; read them back with:
docker compose run --rm --entrypoint sh agent -c 'ls -la $AGENT_WORKSPACE'

# Control plane
docker compose up -d control-plane
open http://localhost:8080
```

The image builds with `mvn verify`, so it cannot be produced from a tree that
fails the tests or the coverage gate. It runs as an unprivileged user (uid 10001)
— the agent executes model-authored test plans, and it should not be root when it
does.

### Kubernetes

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: checkout-performance
spec:
  schedule: "0 2 * * *"          # 02:00 UTC daily
  concurrencyPolicy: Forbid      # never two load tests at once
  jobTemplate:
    spec:
      backoffLimit: 0            # a failed load test is a result, not a retry
      template:
        spec:
          restartPolicy: Never
          containers:
            - name: agent
              image: your-registry/jmeter-agent:1.0.0
              args: ["--mode=API", "--source=/captures/checkout.har"]
              envFrom:
                - secretRef: {name: jmeter-agent-secrets}   # ANTHROPIC_API_KEY, AGENT_SIGNING_KEY
                - configMapRef: {name: jmeter-agent-config} # everything else
              resources:
                requests: {cpu: "2", memory: "4Gi"}
                limits:   {cpu: "4", memory: "8Gi"}
              volumeMounts:
                - {name: captures,  mountPath: /captures, readOnly: true}
                - {name: workspace, mountPath: /var/lib/jmeter-agent/workspace}
          volumes:
            - {name: captures,  configMap: {name: checkout-capture}}
            - {name: workspace, persistentVolumeClaim: {claimName: jmeter-agent-workspace}}
```

`backoffLimit: 0` is deliberate: a failed run has already spent tokens and already
issued load, and retrying it automatically does both again.

---

## 5. CI/CD gate

See [`examples/ci/github-actions.yml`](../examples/ci/github-actions.yml) for a
complete workflow. The shape:

```bash
java -jar agent.jar \
  --mode=OPENAPI --source=openapi.yaml \
  --agent.gate.enabled=true \
  --agent.gate.fail-on-regression=true \
  --agent.gate.max-p95-millis=1500
```

**Exit codes** — these are distinct on purpose, so a pipeline can tell a finding
from a fault:

| Code | Meaning | What the pipeline should do |
| --- | --- | --- |
| `0` | Ran clean, gate passed | Merge |
| `1` | The agent itself failed (bad key, unreachable JMeter, a crash) | Alert whoever owns the agent |
| `2` | The gate blocked the change | Show the developer the percentile table |

**Roll it out in stages.** A gate that blocks a merge the week it is installed,
before anyone trusts the numbers, gets switched off and never switched back on:

1. `AGENT_GATE_ENABLED=false` — the agent runs, produces plans, posts nothing.
2. `enabled=true`, `fail-on-regression=false` — the percentile table appears on
   PRs. Leave it here until the table has been right for a few weeks.
3. `fail-on-regression=true` — now it blocks.

The first run establishes a baseline and cannot regress against anything; the
default `minimum-baseline-runs: 5` means regression detection stays quiet until
there is enough history to be trustworthy.

---

## 6. Security

**The control plane does not authenticate anyone.** It reads identity from
headers (`X-Auth-Subject`, `X-Auth-Tenant`, `X-Auth-Roles`) that it trusts
completely. That is only sound behind a gateway which *strips those headers from
inbound traffic and sets them itself* from a verified session. Exposed directly,
any caller can name their own tenant and roles. The compose file binds it to
`127.0.0.1` for this reason.

**Secrets.**

- The API key belongs in a secret store (Kubernetes `Secret`, AWS Secrets
  Manager, Vault), never in the image, the repo or a command line.
- `AGENT_SIGNING_KEY` must be ≥32 characters — generate with `openssl rand -hex 32`.
  With no key the agent refuses to sign rather than recording a signature that
  proves nothing.
- `.env` is gitignored. Keep it that way.

**Captured traffic is hostile input.** A HAR is a bag of live production
credentials and customer data. Redaction runs before anything reaches the model
— verified live — but:

- `workspace/secrets.properties` holds the *unredacted* values in plaintext, so
  JMeter can bind them at run time. It is as sensitive as the capture. Do not
  archive it as a CI artifact, and mount the workspace with restrictive
  permissions.
- Set `agent.jmeter.strict-compliance=true` to refuse a capture carrying
  regulated material outright rather than sending it in substituted form.

**Blast radius.** The agent issues real load against whatever the capture points
at. Point it at staging. `concurrencyPolicy: Forbid` and `backoffLimit: 0` above
exist to stop it issuing that load twice.

---

## 7. Operating it

**What to keep.** Everything lands in `AGENT_WORKSPACE`:

| File | Keep? |
| --- | --- |
| `auto_test.jmx`, `test_data.csv` | Yes — the plan that ran |
| `results.jtl` | Rotate; these are the big ones |
| `secrets.properties` | **No** — never archive it |
| `run-history.jsonl` | Yes — regression detection reads it; deleting resets every baseline |
| `run-ledger.jsonl` | Yes — the control plane reads it |
| `provenance.jsonl` | Yes — the signed audit trail |
| `heal-memory.jsonl` | Yes — deleting makes the agent re-derive fixes it already knows |

**Cost control.** Every run costs tokens. Set `agent.jmeter.token-budget` to a
hard ceiling per run — an unbounded self-healing loop is an unbounded spend loop.
`agent.jmeter.max-retries` (default 3) bounds it further. Each outcome carries a
token count for chargeback.

**Common failures.**

| Symptom | Cause | Fix |
| --- | --- | --- |
| `401 invalid x-api-key` | Key missing, wrong, or `.env` not sourced | `java -jar` does not read `.env` — `set -a; . ./.env; set +a` |
| `400 invalid_request_error` immediately | You set a Claude 5 model | See §1 Known limitations |
| `Cannot run program ".../jmeter"` | `JMETER_HOME` wrong | It is the `bin` directory, not the install root |
| Every JDBC sampler fails to connect | No JDBC driver | Drop the jar in `<jmeter>/lib/` |
| `SelfHealingFailedException` after 3 attempts | The model could not fix the plan | Read `results.jtl`; often the SUT is down or the capture is stale |
| Gate blocks but you see no table | Report not posted | The agent writes `performance-report.md`; a pipeline step posts it |
| Exit 2 in CI, nobody knows why | Exit 2 is a *gate breach*, not a crash | Surface `performance-report.md` |
