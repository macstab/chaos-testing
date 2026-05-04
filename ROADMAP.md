# Macstab Chaos Testing Framework — Roadmap to 1.0.0

This document tracks the path to the inaugural `1.0.0` public release. The
release integrates two upstream sibling projects into the Testcontainers-based
JUnit 5 framework:

1. **`macstab-chaos-testing-libraries`** — six LD_PRELOAD libraries
   (`libchaos-io`, `libchaos-net`, `libchaos-memory`, `libchaos-time`,
   `libchaos-process`, `libchaos-dns`) covering the libc/syscall boundary across
   `{glibc,musl} × {amd64,arm64}`.
2. **`macstab-chaos-testing-java-agent`** — JVM bytecode agent with 23+
   effects and 60+ instrumentation points covering the application/JDK
   boundary, with Spring Boot 3/4, Quarkus, and Micronaut integrations.

The framework's job is **transport + orchestration + Testcontainers binding**
across both layers behind one declarative annotation surface.

---

## Layering Model

| Layer | Project | Boundary | Strengths |
|---|---|---|---|
| JVM agent | `chaos-testing-java-agent` | JDK API / bytecode | JDBC, HttpClient, async, GC pressure, monitor contention, deadlock, virtual thread carrier pinning, deserialization, JNDI, JMX, ZIP, class loading, ThreadLocal — application-level semantics |
| LD_PRELOAD | `chaos-testing-libraries` | libc / syscall | TORN writes, single-bit corruption, mmap/anon vs mmap/file, mprotect, madvise, posix_spawn/fork/execve, per-fd-path matching — kernel-boundary truth, fires regardless of caller (JNI, JVM internals, native libs) |

**Overlap zones** (DNS, Socket I/O, File I/O, Clock) — both layers can fire on
the same logical operation. Agent intercepts at the JDK API; LD_PRELOAD
intercepts the underlying libc call. The cross-layer bridge (A5) coordinates
this and prevents accidental double-injection.

---

## Module Mapping

### LD_PRELOAD `.so` → owning Java module

The `.so` lives in the **lowest-level** module (closest to the syscall surface);
higher-level modules depend on the owner for the binary.

| `.so` | Owning module | Used transitively by |
|---|---|---|
| `libchaos-io` | `macstab-chaos-disk` | `macstab-chaos-filesystem` |
| `libchaos-net` | `macstab-chaos-network` | `macstab-chaos-connection` |
| `libchaos-memory` | `macstab-chaos-memory` | — |
| `libchaos-time` | `macstab-chaos-time` | — |
| `libchaos-process` | `macstab-chaos-process` | — |
| `libchaos-dns` | `macstab-chaos-dns` | — |

`macstab-chaos-core` keeps the **injector logic** (control plane) but ships **no
binaries**. Each chaos module ships its own `.so` set on the classpath; Java's
classpath aggregates resources at runtime, so consumers only pull the binaries
they actually need as transitive deps.

### LD_PRELOAD effect taxonomy per module

| Module | `.so` | Effects | Errno / extra palette | Notes |
|---|---|---|---|---|
| `disk` | io | `ERRNO`, `LATENCY`, `TORN`, `CORRUPT` | `EIO`, `ENOSPC`, `EDQUOT`, `EROFS`, `EACCES`, `EMFILE`, `ENFILE`, `ENOENT` | `TORN` illegal on `read`; `CORRUPT` is post-success single-bit |
| `network` | net | `ERRNO`, `LATENCY`, `CORRUPT`, `TIMEOUT`, `GAI` | `ECONNREFUSED`, `ETIMEDOUT`, `ECONNRESET`, `EHOSTUNREACH`, `ENETUNREACH`, `EADDRINUSE`, `EADDRNOTAVAIL`, `EPIPE`, `EMFILE`, `ENFILE`, `EAGAIN` | `CORRUPT` only on `recv`; `TIMEOUT` only on `poll`; no `TORN` (datagram semantics) |
| `memory` | memory | `ERRNO`, `LATENCY` | — | sub-selectors `mmap/anon` vs `mmap/file`; `munmap` synthetic-fail leaves mapping alive; **not malloc** |
| `time` | time | `ERRNO`, `LATENCY`, `OFFSET` | `EFAULT`, `EINVAL`, `ENOSYS`, `EINTR` | `OFFSET` only on `clock_gettime` (post-call), not on sleeps |
| `process` | process | `ERRNO`, `LATENCY`, `FAIL_AFTER` | per-symbol palettes (see upstream `PROCESS.md`) | `FAIL_AFTER = ERRNO_NAME,N`; `pthread_create`/`posix_spawn` return errno-as-int |
| `dns` | dns | forward: `GAI`, `LATENCY`, `OVERRIDE`, `FILTER_FAMILY`, `SHUFFLE`, `LIMIT`, `REWRITE`, `SERVICE`. Reverse: `GAI`, `LATENCY`, `REWRITE`, `SERVICE` | `EAI_AGAIN`, `EAI_FAIL`, `EAI_NONAME`, `EAI_MEMORY`, `EAI_SYSTEM` | forward/reverse split enforced at config-parse time |

### JVM agent surface (already provided by upstream — DO NOT rebuild)

**Effects (`ChaosEffect` sealed interface):**

- **Interceptors (8):** `delay`, `gate`, `reject`, `suppress`,
  `exceptionalCompletion`, `exceptionInjection`, `returnValueCorruption`,
  `clockSkew`
- **Stressors (15):** `heapPressure`, `metaspacePressure`,
  `directBufferPressure`, `gcPressure`, `codeCachePressure`,
  `stringInternPressure`, `safepointStorm`, `monitorContention`, `deadlock`,
  `threadLeak`, `threadLocalLeak`, `finalizerBacklog`, `referenceQueueFlood`,
  `spuriousWakeup`, `virtualThreadCarrierPinning`, `keepAlive`

**Operation types** cover thread/virtual-thread lifecycle, executor, queue,
async (CompletableFuture), scheduling, shutdown, class loading, method
enter/exit, monitor/park, JVM runtime (clocks, GC, exit, reflection,
DirectBuffer, ser/de, native lib load, JNDI, JMX, ZIP), NIO, network,
ThreadLocal, HttpClient, JDBC (acquire/execute/prepared/commit/rollback), DNS,
SSL handshake, file I/O.

**Public API:** `ChaosControlPlane`, `ChaosPlan`, `ChaosScenario`,
`ChaosSelector`, `ChaosSession`, `ActivationPolicy`, `ChaosEvent`/`Listener`,
`ChaosMetricsSink`, `ChaosDiagnostics`, typed exceptions.

**Existing framework integrations:** Spring Boot 3/4 starter + test starter,
Quarkus extension (+ deployment), Micronaut integration, testkit, startup
config.

---

## Phases

The roadmap is structured as two parallel tracks (LD_PRELOAD `Phase N` and
agent `Phase AN`) that converge at A5.

### Phase 0 — LD_PRELOAD vendoring (foundation, blocks all libchaos work)

**Goal:** binaries land in the right modules, version-pinned, reproducible.

- `scripts/sync-libchaos.sh <version>` — fetch 24 `.so` from GitHub release,
  verify against `SHA256SUMS`, place in owning module's
  `src/main/resources/libchaos-<lib>/`.
- `LIBCHAOS_VERSION` text file per module dir.
- **Move existing `libchaos-io` blobs:**
  `macstab-chaos-core/src/main/resources/` → `macstab-chaos-disk/src/main/resources/`.
- One-shot sync against libchaos `1.0.0` (all 6 libs, all 4 arch/libc combos).
- **Effort:** S. **Gate:** binaries on classpath, no test breakage.

### Phase A0 — JVM agent vendoring (parallel with Phase 0)

**Goal:** agent jar declared as Maven Central dep, accessible to the framework.

- New module `macstab-chaos-jvm` (sibling to `core`).
- Add `chaos-agent-api` + `chaos-agent-bootstrap` as Gradle deps.
- `JVM_AGENT_VERSION` property in `gradle.properties`.
- Resolve agent jar path at build time; copy into a known classpath resource
  for runtime extraction into containers.
- **Effort:** S.

### Phase 1 — Core abstractions refactor

**Goal:** `core` becomes lib-agnostic transport.

- Rename `SyscallFaultInjector` → `LibchaosTransport` (per-lib loader, generic).
- Generic interface: copy any `.so` into container at `/usr/local/lib/`, set
  `LD_PRELOAD`, write config file, signal reload via `mtime-CAS`.
- Standardized config-delivery contract: `/etc/chaos/<lib>.conf` + reload
  semantics.
- **Effort:** M. **Gate:** existing `macstab-chaos-disk` end-to-end tests still
  pass against the refactored transport.

### Phase A1 — `JvmAgentInjector` transport

**Goal:** activate the agent inside containerized JVMs and the test JVM.

- **Containerized JVM under test:**
  - Copy agent jar into container at `/opt/chaos-agent/chaos-agent-bootstrap.jar`.
  - Inject `JAVA_TOOL_OPTIONS=-javaagent:/opt/chaos-agent/chaos-agent-bootstrap.jar` *before* container start (append, never overwrite).
  - Deploy startup config file (uses agent's existing `chaos-agent-startup-config` format).
  - Port-forward the agent's management endpoint via `withExposedPorts` /
    `getMappedPort`.
- **Test JVM itself:** apply via Gradle `test { jvmArgs("-javaagent:...") }`
  helper.
- **Effort:** M. **Gate:** sample Spring Boot Testcontainer comes up with agent
  active and reachable.

### Phase 2 — Per-module LD_PRELOAD Java APIs (parallelizable across libs)

**Goal:** every `.so` exposed via type-safe Java DSL mirroring the
action × symbol matrix.

For each of the 8 modules (`disk`, `filesystem`, `network`, `connection`,
`memory`, `time`, `process`, `dns`):

- `<Module>ChaosBuilder` — fluent API.
- `<Module>Rule` — typed rule record.
- `<Module>Effect` enum — only effects valid for this module.
- `<Module>Errno` palette — only errnos valid for this module.
- Validation at builder time, not at config-write time — fail loud in Java.

**Effort:** L (8 modules × ~M each). **Parallelizable.** **Gate:** every effect
has an end-to-end test.

### Phase A2 — Agent programmatic API binding

**Goal:** thin client wrapper that talks to the agent's `ChaosControlPlane`.

- HTTP/management-port client in `macstab-chaos-jvm`.
- Builders compose `ChaosPlan` / `ChaosScenario` / `ChaosSelector` directly
  from the agent's API (no parallel API design).
- Lifecycle: scenario activation tied to JUnit `BeforeEach`/`AfterEach`,
  guaranteed deactivation on test failure.
- **Effort:** M. **Gate:** programmatic test that activates `delay` on a
  Spring `@Service` method passes.

### Phase 3 — LD_PRELOAD annotation API

**Goal:** declarative test surface for libchaos modules.

- `@DiskChaos`, `@NetworkChaos`, `@MemoryChaos`, `@TimeChaos`, `@ProcessChaos`,
  `@DnsChaos` — JUnit 5 extensions.
- `target` attribute selects container ref or test JVM.
- Combinable: `@DiskChaos + @NetworkChaos` on the same method composes cleanly.
- **Effort:** M.

### Phase A3 — Agent annotation API

**Goal:** declarative test surface for the agent.

- `@JvmChaos` (general) plus typed sub-annotations: `@JdbcChaos`,
  `@HttpClientChaos`, `@HeapPressure`, `@GcPressure`, `@DeadlockChaos`,
  `@SafepointStorm`, `@MonitorContention`, `@MethodChaos`,
  `@ClassLoadingChaos`, `@DnsChaos` (agent variant).
- Compile-time validation where possible (selector ↔ effect compat); runtime
  validation via the agent's `ChaosValidationException` propagated to JUnit.
- **Effort:** M.

### Phase A4 — Framework starter integration

**Goal:** wire agent's existing starters into Testcontainers-based tests.

- `macstab-chaos-spring-boot3` — depends on `chaos-agent-spring-boot3-test-starter`.
- `macstab-chaos-spring-boot4` — depends on `chaos-agent-spring-boot4-test-starter`.
- `macstab-chaos-quarkus` — depends on `chaos-agent-quarkus-extension`.
- `macstab-chaos-micronaut` — depends on `chaos-agent-micronaut-integration`.
- These modules are **thin** glue, not reimplementations.
- **Effort:** M.

### Phase A5 — Cross-layer coordination (the hard one)

**Goal:** unify both layers behind one annotation surface; coordinate overlap
zones.

- **Bridge annotation:** `@ChaosTest` accepts both LD_PRELOAD and agent rules.
  Apply order: agent activation first, LD_PRELOAD config second; tear down in
  reverse on test exit.
- **Layered scenarios:** scenario builder spans both layers — e.g. "JDBC
  connection acquire delays 500ms (agent) AND TCP connect to db port loses 5%
  packets (LD_PRELOAD net)" expressed as one fluent chain.
- **Layer choice helper:** for overlap zones (DNS / Socket / FileIo / Clock),
  enum `ChaosLayer { AT_JDK, AT_SYSCALL, BOTH }` makes the layering explicit
  and prevents accidental double-injection.
- **Event correlation:** route agent's `ChaosEvent` stream + LD_PRELOAD
  counters into a single test-scoped log.
- **Effort:** L. **Gate:** killer demo — Spring Boot test where JDBC commit
  blocks (agent) while postgres data dir hits ENOSPC (LD_PRELOAD io); assert
  app surfaces a clean error to the user.

### Phase 6 / A6 — Validation suite (joint)

**Goal:** prove every effect on every applicable symbol on every supported
runtime.

- Per-module integration tests covering `(effect × symbol-where-valid)` tuples.
- All 23+ agent effects covered with smoke tests.
- All 60+ `OperationType` values covered where it makes sense.
- Combined LD_PRELOAD + agent test for every overlap zone proving correct
  precedence.
- Spring Boot 3 + 4, Quarkus, Micronaut sample apps each with at least one
  chaos test.
- CI matrix: `{glibc, musl} × {amd64, arm64}` via QEMU + Alpine/Debian
  containers.
- JDK 17 + 21 matrix (the agent needs Loom for `VIRTUAL_THREAD_START`).
- Upstream regression: pin `libchaos N`, also run a nightly against
  `libchaos N+1` to catch breaks early.
- **Effort:** L. **Gate:** all four arch/libc combos green, both JDKs green.

### Phase 7 / A7 — Documentation & 1.0.0 release

**Goal:** docs reflect the full surface; cut GA tag.

- README rewrite — show LD_PRELOAD + JVM agent surface, not just Redis.
- Per-module reference docs linking to upstream technical refs in
  `chaos-testing-libraries/docs/` and `chaos-testing-java-agent/docs/`.
- Cookbook: 10–15 real scenarios, e.g.:
  - Postgres WAL `fsync` EIO
  - Kafka broker network partition + disk pressure
  - Spring Boot under GC pause + DNS flap
  - JDBC commit hang while data dir hits ENOSPC
  - HTTP client retry storm under socket TIMEOUT
  - Virtual thread carrier pinning during file I/O
  - Deserialization gadget rejection under monitor contention
- Cleanup pass: delete the ~25 stale `*_STATUS_*.md` / `SESSION_*.md` files
  from repo root.
- Tag `1.0.0`, publish all modules to Maven Central.
- **Effort:** M.

---

## Parallelization Map

```
Phase 0 (libchaos vendor) ── A0 (agent vendor)
       │                            │
       ▼                            ▼
Phase 1 (core refactor) ──── A1 (JvmAgentInjector)
       │                            │
       ▼                            ▼
Phase 2 (per-lib APIs) ─┬── A2 (programmatic) ── A3 (annotations) ── A4 (starters)
                        │
                        └── A5 (cross-layer bridge) ◀── joins here
                                    │
                                    ▼
                          Phase 6 + A6 (validation, joint)
                                    │
                                    ▼
                          Phase 7 + A7 (docs, joint, 1.0.0 GA)
```

---

## Risks

### LD_PRELOAD-specific

1. **Config-reload race** — `mtime-CAS` reload semantics differ slightly per
   lib; need integration tests proving rules apply within a known-bounded
   window.
2. **Multiple `.so` in `LD_PRELOAD`** — confirm libs don't interpose each
   other's hooks (docs claim `dlsym(RTLD_NEXT)` prevents this — verify).
3. **musl edge cases** — `posix_spawn` cascade gap (glibc uses `CLONE_VFORK`,
   musl uses fork+exec) means process-chaos rules need a runtime libc check.

### Agent-specific

1. **Bytecode instrumentation reach** — if classes load before agent
   activation, instrumentation points won't fire. Mitigate: always use
   `-javaagent`, never runtime attach.
2. **Spring Boot starter version drift** — SB3 + SB4 starters can't coexist in
   the same module. Solution: separate `macstab-chaos-spring-boot3` and
   `macstab-chaos-spring-boot4` modules, mutually exclusive.
3. **Virtual thread chaos requires JDK 21+** — agent probes at startup and
   rejects on older JDKs. Framework must skip those tests cleanly on JDK 17.
4. **`JAVA_TOOL_OPTIONS` collision** — some Spring Boot images preset it. Our
   `-javaagent` flag must append, not overwrite.
5. **Management port exposure** — agent's control plane needs a port. Wrap
   `withExposedPorts(...)` / `getMappedPort(...)` plumbing in the injector.

### Cross-layer

1. **Double-fault inconsistency** — if both layers inject errors on the same
   call, error contracts diverge (agent throws `ChaosJdbcSuppressException`,
   libc returns errno → JDBC driver throws `SQLException`). Cookbook teaches
   users not to double-inject; bridge annotation warns at validation time.
2. **JVM agent + container `--init`** — same init-PID resolution issue the CPU
   module fought through. Agent activation may face similar PID-1 quirks.

---

## Version Targets

Nothing has shipped yet. The first public release is `1.0.0`. Pre-release tags
track milestones inside the GA cycle.

| Tag | Contents |
|---|---|
| `1.0.0-M1` | Phases 0 + 1 + A0 + A1 — vendoring + transports working, no public API yet |
| `1.0.0-M2` | Phase 2 + A2 — feature-complete programmatic surface on both layers |
| `1.0.0-RC1` | Phases 3 + A3 + A4 — annotation API complete, framework starters wired |
| `1.0.0-RC2` | Phase A5 — cross-layer bridge complete |
| `1.0.0` | Phases 6/A6 + 7/A7 — validation green, docs done; **inaugural public release** |

`gradle.properties` stays at `version = 1.0.0` until M1; bump to
`1.0.0-M1-SNAPSHOT` on the first integration branch, walk forward through
milestones, drop the suffix at GA.

Future minor versions (`1.1.0`+) reserved for post-GA additions: upstream lib
chaos types added later, Helidon / Vert.x integrations, Kotlin DSL.

---

## Post-1.0.0 Ideas (Not Committed Scope)

The following are **forward-looking ideas**, not part of the 1.0.0 GA plan.
They are documented here to capture design intent and to keep the 1.0.0 phases
focused. Decisions to commit any of these as 1.x or 2.x phases happen after
GA, based on adoption signal and maintainer capacity.

### Phase 8 (idea) — `macstab-chaos-k8s`: programmatic cluster patching via fabric8

**Concept:** a small, opinionated Java API that uses fabric8 to mutate live
Kubernetes Deployments / StatefulSets / DaemonSets, applying the same chaos
rule types (`DiskRule`, `NetworkRule`, `JvmRule`, …) the test framework
already uses. **Chaos as imperative Java code in a repo**, not GitOps YAML and
not a custom operator.

**Why this fits the framework's identity:**

- Reuses the existing rule taxonomy verbatim — no new DSL, no parallel API.
- Same engineer, same skills, same code idioms as the test-time framework.
- No CRDs, no operator, no admission webhook. Just `client.apps()…edit(...)`.
- "Chaos campaigns as Java" sit naturally next to "chaos tests as JUnit".
- Combines chaos + assertions + revert into one method — awkward in YAML.

**Sketch:**

```java
ChaosCluster cluster = ChaosCluster.fromKubeconfig();

cluster.deployment("user-service")
    .injectDiskChaos(DiskRule.write("/data").errno(EIO).probability(0.05))
    .injectJvmChaos(JvmRule.jdbcCommitDelay(Duration.ofSeconds(2)))
    .applyAndWaitForRollout(Duration.ofMinutes(5));

cluster.statefulSet("postgres-replicas")
    .injectNetworkChaos(NetworkRule.replicationLag(Duration.ofMillis(300)))
    .applyAndWaitForRollout();

// run smoke tests, observe SLOs

cluster.revertAll();
```

**Bidirectional design (the killer property):**

- `ChaosCluster.deployment(...)` patches via fabric8 (k8s mode).
- `ChaosCluster.testcontainer(...)` patches via Docker (test mode — already
  exists in the framework).
- **Same `DiskRule`, `NetworkRule`, `JvmRule` types. Same lifecycle.** Java
  code that runs in a JUnit test can run unchanged against a real cluster.

**Mutation strategies per chaos type** (the API has to be honest about these):

| Chaos type | Application mechanism | Rollout required? |
|---|---|---|
| LD_PRELOAD `.so` | init container drops file; env sets `LD_PRELOAD` | **yes** (process must restart) |
| JVM agent | init container drops jar; env adds `-javaagent` | **yes** (JVM must restart) |
| Network chaos via `tc` | exec into pod | no |
| Disk fill via `dd` | exec into pod | no |
| Chaos rule update (config reload) | patch ConfigMap; lib reloads via `mtime-CAS` | no |

API surface explicitly distinguishes `applyAndWaitForRollout()` from
`applyLive()` so users never get surprised by an unexpected restart.

**Hard parts:**

1. **Rollback discipline.** Mid-campaign crashes leave the cluster in a
   partial state. Solution: persist a "chaos session manifest" in a ConfigMap
   (or local file) listing every mutation; `revert()` reads it and undoes
   deterministically. Idempotent — safe to re-run.
2. **JVM agent timing.** Deployment patch adds `-javaagent`; rolling restart
   must complete before chaos config takes effect. Build in
   `awaitAgentReady()` polling on the agent's management port.
3. **Privileged operations.** `NET_ADMIN`, `SYS_PTRACE`, host PID namespace
   are required for some chaos types. Patcher inspects target's existing
   `securityContext` and either refuses (default) or honors an explicit
   opt-in deployment annotation. Never silently elevates.
4. **Pod selection by labels.** Real teams don't target by Deployment names.
   API must accept `LabelSelector` everywhere a name is accepted.
5. **Auth + execution context.** Default to **dry-run**; require explicit
   `.execute()`. Document service-account RBAC bundles for CI runner usage.
   Support kubeconfig contexts for engineer laptops with audit-trail logging.

**Effort estimate:** ~3–4 months, one engineer.

- ~1 month — `ChaosCluster` + `ChaosTarget` (Deployment / StatefulSet /
  DaemonSet wrappers) + fabric8 plumbing + auth + lifecycle (`apply` /
  `revert`).
- ~2 months — per-chaos-type patching strategies (init container vs. env
  mutation vs. exec-into-pod vs. sidecar-with-shared-PID — each fault type
  needs the right one).
- ~3 weeks — rollback / cleanup primitives, idempotency on retry, partial
  failure recovery, session manifest persistence.
- ~3 weeks — integration tests against `kind` / `k3s`, sample chaos campaign
  repo, docs.

**Why this is the right post-1.0.0 k8s strategy** (vs. alternatives):

| Approach | Reuses test API | Operator? | Effort | Best for |
|---|---|---|---|---|
| **Phase 8 (this idea)** | **yes, fully** | no | 3–4 mo | Java teams running game days, pre-prod chaos campaigns, time-bounded scenarios with revert |
| GitOps YAML wrappers | partially | no | 3–4 mo | Long-running declarative chaos states |
| Chaos Mesh / Litmus adapter | partially | yes (theirs) | 2–3 mo | Teams already on Chaos Mesh |
| Build own operator | yes | yes | 12–18 mo | Becoming a Chaos Mesh competitor |

**Decision needed before committing:** does the team have the capacity to
maintain a fabric8-based code path long-term? fabric8 keeps pace with k8s API
churn but module needs ongoing support across k8s minor versions. Estimate
~10% of one engineer's time for steady-state maintenance once shipped.

**Status:** **idea, not scheduled.** Revisit after 1.0.0 GA based on adoption
signal.

---

## Where to Start

**Next step:** M1 begins with `scripts/sync-libchaos.sh` and the agent dep
declaration in a new `macstab-chaos-jvm` module.
