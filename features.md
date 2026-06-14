# Macstab Chaos Testing Framework — Technical Features

## 1. Overview

The Macstab Chaos Testing Framework is a vertically integrated, three-layer system that enables **Chaos Testing** as a first-class, repeatable tier in the test pyramid. It makes failure modes that are unreachable by any other practical tool reproducible, composable, and fully reversible inside ordinary JUnit 5 + Testcontainers suites running on unmodified production container images.

It spans three layers that share a consistent mental model (selector × effect × activation policy) while operating at completely different visibility and trust boundaries:

- **C LD_PRELOAD layer** (`chaos-testing-libraries`): libc symbol interposition for any Linux process.
- **JVM bytecode agent layer** (`chaos-testing-java-agent`): in-process instrumentation of JDK surfaces.
- **Orchestration layer** (`chaos-testing`): annotation-driven composition, transparent container preparation, and lifecycle management.

The system supports arbitrary runtime composition of any subset of the six C domains, hot removal of individual effects without restarting the target process or container, and transparent operation against any glibc- or musl-based Linux image (Alpine, distroless, scratch-derived, UBI, etc.) with zero changes to the application artifact.

## 2. Architectural Context

Three layers with deliberately narrow, independently usable contracts:

- The C layer publishes no public SDK. Its interface is a set of per-domain plain-text configuration files plus the standard libc symbols it interposes. Multiple domains can be active simultaneously; any subset can be removed at runtime by config change.
- The JVM agent publishes a stable API (`chaos-agent-api`) containing sealed hierarchies for selectors and effects plus records for scenarios and policies. Everything below the API (bootstrap bridge, instrumentation details, evaluation engine) is internal.
- The orchestration layer owns environment mutation (`LibchaosTransport` for pre-start `.so` injection, `PackageManager` for distro-agnostic tool installation) and declarative composition via L1/L2/L3 annotations and the `ChaosPlugin` SPI.

Trust boundaries are explicit. The test author controls scope (per-session vs JVM-global). The framework owns preparation and cleanup. The application under test sees only the observable effects of the declared faults. No assumptions are made about the application's resilience mechanisms.

Deployment context is always test execution (local or CI) against Linux containers. The C layer is injected into target containers via Docker API copy before start; the JVM agent is attached to the test JVM (or application JVMs via starters).

## 3. Key Concepts and Terminology

- **Domain**: A distinct fault surface (network, DNS, file I/O, memory, process lifecycle, time).
- **L1 primitive**: Direct operation-level fault (e.g., `ECONNRESET` on `recv`, `ENOMENT` on `mmap`, carrier pinning via monitor contention).
- **L2 composite**: Named, documented combination of L1 primitives within one domain with sane defaults.
- **L3 incident**: Cross-domain, cross-layer named scenario that reproduces a specific class of real production failure (e.g., rolling deploy RST storm, JDK 21 virtual thread carrier pinning, code cache exhaustion under load, Feign retry amplification during brownout).
- **LibchaosTransport**: Orchestration component that inspects a container image, selects the correct pre-built `.so` variant (glibc vs musl × amd64 vs arm64), copies it into the container via the Docker API *before* start, and sets `LD_PRELOAD`.
- **PackageManager abstraction**: Single API that auto-detects the container's package manager (APT, APK, DNF, YUM, Pacman, Zypper) and installs required host tools (e.g., `iproute2` + `iptables`) for network shaping when pure syscall faults are insufficient.
- **ActivationPolicy**: Record controlling probability, warm-up count (`activateAfterMatches`), hard cap (`maxApplications`), duration (`activeFor`), sliding-window rate limit, random seed, and destructive-effects flag.
- **Session isolation**: `ChaosSession` + task decoration that propagates identity into `ThreadPoolExecutor` submissions and `CompletableFuture` callbacks so concurrent tests in the same JVM never see each other's effects.
- **Runtime reversibility**: Addition or removal of any combination of domains or individual effects while the target process and container continue running (no restart, no new image).

## 4. End-to-End Behavior

When a test class or method carries chaos annotations:

1. `ChaosTestingExtension` (driven by `@ChaosTest` meta-annotation or explicit registration) processes container annotations via the `ChaosPlugin` SPI.
2. `@SyscallLevelChaos` annotations trigger `LibchaosTransport.prepare(...)` for each declared `LibchaosLib` *before* `container.start()`. The transport performs image inspection, selects and injects the matching pre-built `.so` variants, and sets `LD_PRELOAD`.
3. If network shaping is required, `PackageManager.detect(container).install(...)` runs (one API, six package managers).
4. L1/L2/L3 annotation processors walk the annotated element, resolve composers via `@ChaosL3` meta-annotations, and apply rules to matching containers.
5. During execution:
   - Every intercepted libc symbol evaluates the current (hot-reloaded) rule set under thread-local reentrancy guards.
   - Every instrumented JDK call site evaluates the 8-gate pipeline (started → session match → selector match → warm-up → rate limit → probability → max-applications CAS).
6. On completion the processors invoke `removeAll` on returned handles. C-layer config files are cleared; JVM agent scenarios are unregistered. Containers follow normal Testcontainers lifecycle.

Any subset of the six C domains can be active simultaneously. Any subset can be removed at any time without affecting the others or requiring container restart. The JVM agent can be active in the same test for hybrid syscall + bytecode faults.

## 5. Architecture Diagrams

### Component Diagram — Vertical Integration

```plantuml
@startuml
title Three-Layer Vertical Integration with Consistent Selector × Effect × Policy Model

package "Orchestration (chaos-testing)" {
  [ChaosTestingExtension]
  [L1/L2/L3AnnotationProcessor]
  [LibchaosTransport]
  [PackageManager (6 distros)]
  [ChaosPlugin SPI]
}

package "JVM Agent (chaos-testing-java-agent)" {
  [chaos-agent-api (stable)]
  [ByteBuddy Advice (57+ handles)]
  [Bootstrap Bridge (volatile publication)]
  [ScenarioController (8-gate pipeline + CAS)]
  [Stressors (heap, metaspace, code-cache, safepoint, deadlock, thread-leak, ...)]
}

package "C LD_PRELOAD (chaos-testing-libraries)" {
  [libchaos-net.so]
  [libchaos-dns.so]
  [libchaos-io.so (torn + corrupt)]
  [libchaos-memory.so]
  [libchaos-process.so]
  [libchaos-time.so]
  [Per-domain hot-reloadable config]
  [Thread-local reentrancy + fd cache]
  [Symbol-ownership invariant + AT_SECURE]
}

[ChaosTestingExtension] --> [LibchaosTransport] : prepare before start
[ChaosTestingExtension] --> [ChaosPlugin SPI]
[LibchaosTransport] ..> [libchaos-*.so] : Docker copy + LD_PRELOAD
[ByteBuddy Advice] ..> [Bootstrap Bridge] : JMM-safe dispatch
[ScenarioController] ..> [libchaos-*.so] : hybrid faults
[libchaos-*.so] --> libc : PLT/GOT interposition

note right of [LibchaosTransport]
  Auto glibc/musl + arch selection
  Any production image (distroless, Alpine, scratch)
end note

note right of [Bootstrap Bridge]
  appendToBootstrapClassLoaderSearch
  volatile handles then delegate
  reentrancy ThreadLocal<int[]>
end note

note right of C layer
  Separate .so per domain enables true runtime composition + removal
end note
@enduml
```

**Takeaway**: Orchestration owns preparation and declarative composition. The two execution layers are independently usable and fully composable at runtime with independent removal.

## 6. Component Breakdown

**LibchaosTransport**
- Responsibility: image inspection, variant selection, pre-start Docker API copy, `LD_PRELOAD` setup, later rule removal.
- Why pre-start injection via Docker API copy (instead of volumes, init containers, or sidecars): guarantees the library is present before the dynamic linker runs; works on images with no package manager and no outbound network; requires zero changes inside the shipped application image.
- Why image-name heuristic for glibc vs musl: reliable for the common cases while remaining extremely lightweight.

**PackageManager**
- Single `detect` + `install` API over six package managers.
- Why: network shaping often needs `tc` + `iptables`; without this, every test author would write per-distro setup code.

**L*AnnotationProcessor + L3Composer**
- The processors walk annotations, expand repeatables, filter by `id()`, resolve composers via the FQN in `@ChaosL3`, and delegate.
- Composers are the only place that knows how to turn a high-level named incident into concrete rules across domains.
- Why the split: the processor provides uniform scanning, id filtering, error handling, reporting, and removal scaffolding; each L3 can have its own composition logic and description.

**ChaosPlugin SPI (ServiceLoader)**
- Decouples universal extension logic (lifecycle, resources, chaos orchestration) from container-specific details (image, ports, connection info).
- This eliminated per-container JUnit extension duplication while preserving type-safe parameter injection.

**JVM Agent — Bootstrap Bridge and 8-Gate Pipeline**
- JDK classes are loaded by the bootstrap classloader → a tiny dispatcher must be appended via `Instrumentation.appendToBootstrapClassLoaderSearch`.
- Visibility is established with a volatile two-field publication (`MethodHandle[] handles` then `delegate`) so that once the delegate is visible the handles are guaranteed initialized (JSR-133 happens-before).
- Reentrancy guard uses `ThreadLocal<int[]>` (primitive array to avoid boxing) with explicit `remove()` in the finally block to prevent per-thread leaks on pooled threads.
- The evaluation pipeline is deliberately short-circuiting and mostly lock-free. The only `synchronized` is the rate-limit window. `maxApplications` uses a CAS loop (not increment-then-check) because the latter allows all racing threads to pass the guard and overshoot the cap.
- `SplittableRandom` (new instance per evaluation) is used for reproducibility under a fixed seed instead of `ThreadLocalRandom`.

**C Layer — Symbol Ownership, Hot Reload, and Guards**
- Separate `.so` per domain is a deliberate invariant: each library owns a disjoint set of symbols. This enables true runtime composition (any subset active together) and independent removal.
- Config reload is lock-free for readers (writer publishes a new snapshot atomically).
- FD cache (I/O domain) resolves paths via `/proc/self/fd` and maintains thread-local mappings because many syscalls only receive an fd. The cache is invalidated on `close`, successful `unlinkat`, and `renameat`.
- Thread-local reentrancy guard + per-thread PRNG in every library.
- Formal symbol-ownership theorem: once a library has successfully interposed a symbol via `dlsym(RTLD_NEXT)`, it owns that symbol for subsequent application calls for the life of the process.
- Fail-open on any config parse error or `AT_SECURE` environment.

## 7. Data Model and State

- C layer: per-domain text configs + thread-local rule snapshots + fd cache. Snapshot swap is the only writer coordination.
- JVM agent: `ScenarioRegistry` of `ScenarioController` instances. Each controller owns `AtomicLong` matched and applied counters plus an immutable `ActivationPolicy`.
- Orchestration: transient `AppliedL*` handle objects returned by composers; these are the only things the extension must retain for later removal.

Invariants:
- An exhausted controller (hit `maxApplications`) remains registered until explicitly removed.
- Session-scoped effects are invisible to threads not bound to that session, even across executor hand-offs.
- C-layer configs and JVM scenarios are completely independent; only the orchestration layer composes them.

## 8. Concurrency and Threading Model

C layer: lock-free reader path for the current rule snapshot; writer uses atomic snapshot swap. Thread-local reentrancy guard + per-thread PRNG in every library.

JVM agent: evaluation is lock-free except the rate-limit `synchronized` block and the CAS loop on `appliedCount`. Session identity is carried by decorating tasks at submission time. The reentrancy guard prevents chaos code (which itself calls instrumented JDK methods such as `Thread.sleep` or `LockSupport.park`) from recursively triggering instrumentation.

Virtual-thread awareness: the agent instruments AQS-based locking (not raw `synchronized`) and is explicitly aware that carrier pinning can still occur when the workload uses `synchronized` on hot paths under high virtual-thread concurrency (JEP 444).

JMM relevance: the bootstrap bridge uses volatile write ordering to guarantee that once `delegate` is visible, the `MethodHandle[]` array is fully initialized.

## 9. Error Handling and Failure Modes

- Missing L3 composer class at application time → `ExtensionConfigurationException` that explicitly names the required testpack module.
- No container matching an annotation's `id()` → immediate failure listing available container ids.
- C-layer config parse error on any domain → that domain falls back to passthrough (fail-open).
- Reentrancy without guard → would cause unbounded recursion; the primitive-array depth guard plus identity check on the depth `ThreadLocal` itself prevents this.
- Partial removal on cleanup → best-effort; individual failures are logged but do not abort the rest.

"Missing module for the annotation you wrote" is treated as a test configuration error, not a silent no-op.

## 10. Security Model

The framework requires Docker socket access and the ability to copy files into target containers and set their environment before start. It can also install packages when network tools are required.

It deliberately does not sandbox the faults it injects. The threat model assumes the test author is trusted and the goal is to reproduce production failure modes.

All C libraries check `AT_SECURE` and refuse to operate when the dynamic linker would ignore `LD_PRELOAD` for security reasons.

## 11. Performance Model

C layer hot path: one `stat` + rule match + thread-local guard per intercepted syscall. Designed for the case where most calls have no matching rule.

JVM agent hot path: after JIT the advice is inlined. When no scenarios are active the fast path is a volatile read of a flag plus an empty `ConcurrentHashMap` check. Measured overhead when idle is intended to be negligible relative to the cost of the underlying JDK operation.

Stressors are explicitly bounded (by `activeFor` or manual handle close) so they do not leak resources across test boundaries.

## 12. Observability and Operations

`ChaosApplicationReport` records every applied L1/L2/L3 event with scope and severity; the test can assert on it or log it.

The JVM agent exposes `ChaosDiagnostics` (per-scenario matched/applied counts and last state change reason) plus a JMX MBean.

C-layer effects are visible only through application behavior; the libraries contain no side-channel metrics export by design (they remain minimal and language-agnostic).

## 13. Configuration Reference

- C layer: per-domain files under `/tmp/.chaos-*.conf` with line format `selector:effect:value[@probability]`.
- JVM agent: `ActivationPolicy` record (probability, `activateAfterMatches`, `maxApplications`, `activeFor`, optional `RateLimit`, optional seed, destructive flag).
- Orchestration: annotation attributes plus the declarative L3 annotations.

## 14. Extension Points and Compatibility Guarantees

- `ChaosPlugin<A>` SPI — add support for a new container type without touching core chaos logic.
- `L3Composer` implementations registered via the `composer` attribute on `@ChaosL3`.
- Programmatic `ChaosControlPlane` / `ChaosSession` API for tests that prefer code over annotations.

The published surfaces (`chaos-agent-api`, the annotation set, `ChaosPlugin` interface) are stable. Internal composers, the exact set of instrumented JDK methods, and per-domain config grammar are not.

## 15. Stack Walkdown

**Annotation and orchestration layer**  
Annotations are the stable user contract. The extension and processors translate them into environment preparation and rule application. This layer owns the "one annotation = complex cross-layer incident" experience and the guarantee that effects will be removed on test completion.

**JVM agent layer**  
ByteBuddy advice is copied into target method bodies at retransformation time. The bootstrap bridge exists because the only way to intercept methods on classes loaded by the bootstrap classloader is to have code visible to that classloader. The volatile two-field publication (`handles` then `delegate`) plus JMM happens-before guarantees visibility without a full memory barrier on every hot-path call. The `ThreadLocal<int[]>` reentrancy guard with explicit `remove()` in finally is required to prevent per-thread leaks on pooled threads under sustained load. The CAS loop on `appliedCount` is required to prevent concurrent overshoot of `maxApplications`. The agent is explicitly aware of virtual thread carrier pinning (JEP 444) and instruments AQS-based locking rather than raw `synchronized`.

**C LD_PRELOAD layer**  
Interposition happens at PLT/GOT resolution time by the dynamic linker. Separate `.so` per domain is a deliberate invariant that enables true runtime composition (any subset active together) and independent removal. Config hot-reload via atomic snapshot swap is the minimal mechanism that gives scriptable, language-agnostic control without background threads. The fd cache via `/proc/self/fd` plus invalidation on `close`/`unlinkat`/`renameat` is required for correctness on fd-only syscalls. The formal symbol-ownership theorem (once interposed via `dlsym(RTLD_NEXT)`, the library owns the symbol for subsequent application calls) plus `AT_SECURE` checking guarantees safe behavior across glibc and musl on multiple architectures.

**OS / kernel / container layer**  
`LD_PRELOAD` is processed by the dynamic linker before `main`. Pre-start Docker API injection means the library is present in the container's filesystem namespace with no network or package installation required from inside the application image. The PackageManager abstraction exists solely because "install the tools for network shaping" is otherwise a per-distro maintenance nightmare.

**Infrastructure layer**  
Docker socket access and the ability to run containers that accept `LD_PRELOAD` (and `NET_ADMIN` when shaping is required) are external operational prerequisites. The framework does not attempt to hide these realities.

## 16. References

- JSR-133 — Java Memory Model (volatile publication, happens-before)
- JEP 444 — Virtual Threads (carrier pinning semantics)
- Java Virtual Machine Specification (monitorenter, retransformation constraints, intrinsics)
- ELF gABI and GNU symbol versioning (PLT/GOT interposition, versioned symbols)
- ld.so(8) — dynamic linker behavior and `LD_PRELOAD` semantics
- Docker Engine API — container filesystem copy and environment injection before start
- glibc and musl libc documentation for the interposed symbols
- Open Group Base Specifications for the POSIX functions interposed by the C layer

The combination that is genuinely rare — and what makes strong engineers do a double-take — is this:

You can write a single annotation on a test class (or method, or container) that simultaneously injects realistic, composable, and independently removable syscall faults (any combination of net/dns/io/memory/process/time) *and* deep JVM-internal faults (carrier pinning on JDK 21+ virtual threads, JIT code cache exhaustion, real safepoint pressure while requests are in flight, etc.) into the *exact* production image you ship, with full session isolation so parallel tests in the same JVM do not interfere, and have the entire scenario cleaned up automatically when the test ends — all without changing the application code or the container image.

That capability, delivered with the engineering rigor visible in the C-layer symbol-ownership invariant, the JMM-correct bootstrap bridge, the lock-free hot-reload model, the 100% coverage + sanitizer + Docker matrix quality gates, and the L3 named-incident vocabulary, is what distinguishes this system. It turns post-mortem war stories into reproducible, pipeline-blocking tests.