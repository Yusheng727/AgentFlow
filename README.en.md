# AgentFlow

[![CI](https://github.com/Yusheng727/AgentFlow/actions/workflows/ci.yml/badge.svg)](../../actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Maven](https://img.shields.io/badge/Maven-multi--module-81684b)

**A Java-native, lightweight multi-agent orchestration engine** — declare workflows in YAML DSL, drive them with a BSP (Bulk Synchronous Parallel) execution model, recover from crashes with a two-level checkpoint protocol, and let multiple agents collaborate like microservices.

**Evolution spine**: static DAG → conditional routing (`when` predicates + `on_error` fallback) → iterative convergence (bounded loops / back-edges) → Kafka submit/execute decoupling → Human-in-the-Loop approvals + RAG extension points. Every step keeps one invariant: *checkpoint routing-decision replay buys back determinism*.

[中文 README](README.md) ｜ [Architecture diagrams](docs/design/) ｜ [User guide (中文)](docs/USER_GUIDE.md)

## Architecture

**What the system is made of** (module layers, [full image](docs/design/agentflow-layers.png)):

![Module layers](docs/design/agentflow-layers.png)

Bottom-up: infrastructure (PostgreSQL / Kafka / Grafana / LLM providers) → DSL parsing & layering → engine core (BSP execution / two-level checkpoint / recovery protocol / fault chain / column encryption) → agent adapters (Spring AI / LangChain4j / Mock / RAG, all plugged through the single `AgentFunction` contract — the engine has zero framework dependencies) → delivery (REST API / React UI / Spring Boot Starter).

**What happens during a run** (BSP timeline, [full image](docs/design/agentflow-execution.png)):

![BSP execution timeline](docs/design/agentflow-execution.png)

Within a super-step, nodes run in parallel on virtual threads, each seeing only a read-only snapshot (no shared mutable state). At the barrier (amber line), `CompletableFuture.allOf` synchronizes globally, then channels merge deterministically in declaration order. Two checkpoint levels write at node completion and after each barrier — after a crash, only the crashed layer's unfinished nodes re-run; nothing is billed twice.

## Quick start (5 minutes, mock mode, zero LLM cost)

```bash
# Prerequisites: JDK 21 + Maven 3.9+
git clone https://github.com/Yusheng727/AgentFlow.git
cd AgentFlow

# 1. First build: install engine modules into your local repo
mvn -B -ntp install -DskipTests

# 2. Run the supplier-risk demo (mock mode — no LLM API calls)
mvn -B -ntp -pl demo-supplier-risk spring-boot:run
```

Output (excerpt):

```
=== Supplier Risk Assessment ===
{"riskLevel":"LOW","confidence":0.85,"evidence":["financials healthy","1 compliance violation","solid reputation"],"recommendation":"Proceed, monitor environmental compliance"}
```

**What happened?** Three expert agents (finance / compliance / reputation) analyzed in parallel in super-step 0 → barrier sync → the Supervisor aggregated a rating in super-step 1. `MockAgentFunction` reads canned responses from the YAML `mock_response` field, validating BSP topology and `${channel}` SpEL references without hitting any LLM.

**With the Web UI** (six-tab console: dashboard / submit / definitions / approval center / trace / diagnosis; falls back to mock data when the backend is down):

```bash
mvn -B -ntp -pl demo-api spring-boot:run     # mock-only REST server (:8080, zero LLM, zero DB)
cd agentflow-ui && npm install && npm run dev  # http://localhost:5173 (Vite proxy → :8080)
```

**Try Human-in-the-Loop** (demo-api registers an approval-gate agent; submitting a workflow with an `agent: approval` node triggers the full chain: pause → `AWAITING_APPROVAL` → approve → resume):

```bash
# 1. Submit a workflow with an approval gate → engine pauses at AWAITING_APPROVAL
curl -X POST localhost:8080/api/workflows \
  -H 'X-API-Key: demo-key-1234567890abcdef' -H 'Content-Type: application/json' \
  -d '{"workflowName":"hitl-demo","version":"1.0","inputs":{},
       "yamlContent":"agentflow: {version: \"1.0\"}\nnodes:\n  - {id: gate, agent: approval, prompt_template: \"Payment approval\"}\n  - {id: after, agent: mock, mock_response: \"done\"}\nedges:\n  - {from: gate, to: after}\n"}'

# 2. List pending approvals (or use the UI approval-center tab)
curl -s localhost:8080/api/approvals/pending -H 'X-API-Key: demo-key-1234567890abcdef'

# 3. Approve → engine resumes from the approval layer to SUCCESS
curl -X POST localhost:8080/api/workflows/<wfId>/approvals/<approvalId> \
  -H 'X-API-Key: demo-key-1234567890abcdef' -H 'Content-Type: application/json' \
  -d '{"decision":"APPROVE"}'
```

## Core features

**Orchestration**

- **YAML DSL** with three-layer validation (Jackson types → semantics → DAG integrity); `on_error` and back-edges are part of cycle checking
- **BSP execution**: virtual threads + `CompletableFuture.allOf` barrier + read-only snapshots + declaration-order reducer merging — lock-free concurrency, same input → same output
- **Conditional routing**: edge-level `when` predicates (hardened SpEL, no `T()`) + `on_error: goto` fallback; the static graph stays pre-layered, each layer runs only reachable nodes, unreachable downstream is marked SKIPPED
- **Iterative convergence**: `loop: true` back-edges with `max_iterations`; checkpoints persist routing decisions per iteration round and replay them on recovery

**Reliability**

- **Two-level checkpoint**: node-level (write on completion — no double LLM billing) + barrier-level (layer-merge snapshots — the recovery boundary); PostgreSQL / in-memory implementations, idempotent writes with throttling
- **Recovery protocol**: locates the crashed layer from the latest barrier, replays acknowledged outputs, re-runs only unfinished nodes; under dynamic routing / loops it recomputes reachability from persisted taken-edges — SKIPPED nodes stay skipped, nothing is billed twice
- **Fault chain**: Timeout → ErrorClassifier (Transient/Fatal) → Retry (exponential backoff, transient only) → ErrorHandler, with a combined budget cap

**Human-in-the-Loop**

- Full approval chain: agent throws `ApprovalRequiredException` → engine pauses at the barrier (sibling outputs snapshotted so they won't re-run) → `AWAITING_APPROVAL` → approve/reject via REST or UI → resume from the approval layer; multi-level approval chains supported; the approver identity is derived server-side from credentials
- Approval-center Web UI: cross-workflow pending aggregation, a dedicated "awaiting approval" board column; approval writes have no mock fallback

**Security**

- API-key auth (SHA-256 hashed) + ownership checks (anti-IDOR) + per-caller tool authorization (config ∪ runtime DB grants; grant/revoke admin-only) + a pre-submit guard (node-count / estimated-cost limits → 422)
- **Column-level encryption at rest**: five sensitive JSONB columns behind one `ColumnEncryptor` boundary — AES-256-GCM, `AESGCM:` self-describing prefix, legacy plaintext compatible reads, production wiring is `fromEnvStrict()` **fail-closed** (missing key refuses startup)
- Credential discipline: LLM keys only from env vars; prompts/traces redacted by default

**Portability & extensibility**

- **Two framework adapters** (Spring AI, LangChain4j) behind one narrow surface; the second adapter's dependency list does not include the first framework — build-level replaceability proven; engine/DSL/upstream untouched
- **RAG extension point**: `demo-rag` shows an `agent: rag` node doing retrieve→augment→delegate with zero engine changes (`RagEngineZeroChangeTest`) — extension points hold by contract, not by engine modification
- **Mock LLM**: zero-cost local debugging with `${channel}` placeholder context passing

**Production**

- **Kafka async dispatch**: submit/execute decoupled (`agentflow.kafka.enabled` opt-in); consumer `tryClaim` is atomic idempotent + terminal-state skip, so at-least-once delivery can't double-run or double-bill
- **Observability**: 5 Micrometer metric families + 6 Grafana panels (one `docker compose --profile observability up` away) + ExecutionTrace recording tokens/latency/routing decisions per step
- **Definition versioning**: stored by `(name, version)`; running instances finish on their old DAG; conflict detection warns without blocking

## Run modes

| Mode | Config | Checkpoint | Use case |
|:---|:---|:---|:---|
| **Zero infra (mock)** | `agentflow.mock.enabled=true` (default) | In-memory | Local debugging, development |
| **Production** | `agentflow.mock.enabled=false` + PG DataSource | PostgreSQL (durable + encrypted) | Production |
| **Kafka async** | `agentflow.kafka.enabled=true` | PostgreSQL | Decoupled submit/execute, idempotent consumers |

## Production checklist

1. **Configure a PostgreSQL DataSource** (`spring.datasource.url=...`; Flyway migrations V1–V8 run automatically)
2. **Set the LLM API key** (env var only): `export SPRING_AI_OPENAI_API_KEY=sk-xxx`
3. **Set the encryption key** (fail-closed): `export AGENTFLOW_ENCRYPTION_KEY=<base64(32B)>`
4. **Set API + admin keys**: `export AGENTFLOW_API_API_KEYS=<key>` and `export AGENTFLOW_ADMIN_API_KEYS=<admin-key>`
5. **Turn off mock mode**: `agentflow.mock.enabled=false`
6. **(Optional) Kafka**: `agentflow.kafka.enabled=true` + `spring.kafka.bootstrap-servers=...`
7. **Start**: `docker compose --profile production up`

## FAQ

**Q: How does BSP handle conditional branches and loops?**

Branches use *reachability pruning*, not abandoning BSP: the static graph stays pre-layered; each layer runs only reachable nodes and pruned downstream is marked SKIPPED. Loops use layering-exempt back-edges + outer iteration rounds; checkpoints persist routing decisions per round and replay them on recovery. Both preserve the "barrier sync + deterministic merge" invariant.

**Q: What about existing plaintext rows after enabling encryption?**

The `AESGCM:` prefix is self-describing — the decryptor passes non-prefixed values through untouched, so legacy plaintext rows read normally after upgrade. Encryption applies to new writes (fail-closed in production wiring); existing rows aren't backfilled. Key rotation is deferred.

**Q: How do I add a new agent?**

Register an `AgentFunction` (the engine's single contract) on the `NodeRegistry`:

```java
@Bean
AgentFunction myAgent() {
    return input -> AgentOutput.of("hello");
}
```

**Q: Why not use an existing orchestration framework?**

AgentFlow is a from-scratch learning / showcase project — the goal is not to fill an ecosystem gap (LangGraph4j and Spring AI Alibaba exist), but to get the full decision chain right for distributed-systems problems: a BSP engine, two-level checkpointing, deterministic recovery. See [developer-notes/01-implementation-rationale](docs/developer-notes/01-implementation-rationale.md) for the trade-offs.

## Repo layout

```
AgentFlow/
├── agentflow-core/               # BSP engine / DSL / checkpoint / fault / encryption (zero framework deps)
├── agentflow-adapters/spring-ai/ # SpringAiAgentAdapter + Mock LLM
├── agentflow-adapters/langchain4j/ # Second adapter (build-level replaceability)
├── agentflow-api/                # REST endpoints / auth / approvals / diagnosis / submit guard
├── agentflow-kafka-starter/      # Kafka submit/execute decoupling (property-gated)
├── agentflow-starter/            # @EnableAgentFlow / AutoConfiguration
├── agentflow-ui/                 # React 18 six-tab console
├── demo-*/                       # 7 runnable demos (parallel fan-out, serial chain, fork-join,
│                                 #   conditional routing, reflection loop, RAG, REST server)
├── docs/
│   ├── design/                   # Architecture diagrams + render scripts
│   ├── developer-notes/          # Implementation rationale / bugs & fixes / review findings
│   ├── business/                 # Business-flow docs (11 flows)
│   ├── plans/agentflow/          # Design decision records (KTD-1~9)
│   └── USER_GUIDE.md / TROUBLESHOOTING.md / GRAFANA.md / ROADMAP.md
└── docker-compose.yml            # mock / production / distributed / observability profiles
```

## Build

```bash
mvn verify                          # Full build (622 Java tests + JaCoCo 80% gate + integration tests)
mvn -pl agentflow-core test         # Core tests only
cd agentflow-ui && npm test         # UI Vitest (34 cases)
cd agentflow-ui && npm run build    # tsc strict build
```

## Tech stack

Java 21 (virtual threads) / Spring Boot 4.1 / Spring AI 2.0 / LangChain4j / PostgreSQL + Flyway / Kafka / Micrometer + Grafana / React 18 + Vite + TypeScript / Maven multi-module (13 modules)

## License

[MIT](LICENSE)
