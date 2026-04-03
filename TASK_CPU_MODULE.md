# CPU Chaos Module — Task List

**Date:** 2026-04-01  
**Module:** `macstab-chaos-cpu`  
**SPI:** `macstab-chaos-core` → `CpuChaos`  
**Existing impl:** `CgroupsCpuChaos.java` (partially working)  
**Branch:** `feat/cpu-chaos`

---

## Phase 1: Fix Existing Code

### Task 1.1 — Fix CgroupsCpuChaos Basics
**File:** `CgroupsCpuChaos.java`  
**Effort:** 30min

- [ ] Add `@Slf4j` annotation (log is used but annotation missing)
- [ ] Fix Javadoc: class says "cgroups v2 cpu.max" but implementation uses `cpulimit` — correct the docs to match reality
- [ ] Verify `Shell.SH` / `Shell.FLAG_C` imports resolve (class uses `com.macstab.chaos.core.util.Shell`)
- [ ] Verify `PackageInstaller.install(container, "stress-ng", "cpulimit")` works on both Debian and Alpine

### Task 1.2 — Implement getCurrentUsage()
**File:** `CgroupsCpuChaos.java`  
**Effort:** 2h

Current state: `return 0; // TODO`

Implementation approach — two-sample `/proc/stat` delta:

- [ ] Read `/proc/stat` first line: `cpu <user> <nice> <system> <idle> <iowait> <irq> <softirq> <steal>`
- [ ] Parse all fields into longs
- [ ] Sleep 500ms (configurable internally, not exposed)
- [ ] Read `/proc/stat` again
- [ ] Calculate: `total_delta = sum(all_fields_delta)`, `idle_delta = idle + iowait delta`
- [ ] Return: `(int) ((total_delta - idle_delta) * 100 / total_delta)`
- [ ] Handle edge cases: division by zero (container just started), negative delta (counter wrap)
- [ ] Extract parsing into `private static long[] parseCpuStat(String line)`
- [ ] Tests: mock container exec returning known `/proc/stat` values

### Task 1.3 — ServiceLoader Registration
**Files:** `META-INF/services/com.macstab.chaos.core.api.CpuChaos`  
**Effort:** 15min

- [ ] Create/verify `src/main/resources/META-INF/services/com.macstab.chaos.core.api.CpuChaos` containing `com.macstab.chaos.cpu.CgroupsCpuChaos`
- [ ] Verify `ServiceLoaderRegistrationTest` passes
- [ ] Verify `ChaosProviderRegistry` can discover it

---

## Phase 2: New SPI Methods

### Task 2.1 — CpuChaos SPI Extension
**File:** `CpuChaos.java` (in `macstab-chaos-core`)  
**Effort:** 1h

Add to `CpuChaos` interface:

```java
/**
 * Returns the number of CPU cores visible to the container.
 *
 * @param container target container (must be running)
 * @return number of available CPU cores
 */
int getAvailableCores(GenericContainer<?> container);

/**
 * Throttle CPU with automatic reset after duration.
 *
 * @param container target container
 * @param percentage CPU percentage (1-100)
 * @param duration throttle duration, auto-resets after
 */
void throttle(GenericContainer<?> container, int percentage, Duration duration);

/**
 * Spawn CPU stress workers AND cap total CPU via cpulimit.
 * Simulates cgroup-limited noisy neighbor (Kubernetes pod limits).
 *
 * @param container target container
 * @param workers number of stress-ng workers
 * @param percentage maximum CPU percentage (cpulimit cap on stress-ng)
 */
void stressWithThrottle(GenericContainer<?> container, int workers, int percentage);

/**
 * Check if CPU throttle (cpulimit) is currently active.
 *
 * @param container target container
 * @return true if cpulimit is running
 */
boolean isThrottled(GenericContainer<?> container);

/**
 * Check if CPU stress (stress-ng) is currently active.
 *
 * @param container target container
 * @return true if stress-ng is running
 */
boolean isStressed(GenericContainer<?> container);
```

- [ ] Full Javadoc with examples and use cases for each method
- [ ] Update `NoOpCpuChaos` with new methods (throw `ChaosProviderNotFoundException`)

### Task 2.2 — Implement getAvailableCores()
**File:** `CgroupsCpuChaos.java`  
**Effort:** 30min

- [ ] Execute `nproc` inside container
- [ ] Parse stdout to int
- [ ] Fallback: if `nproc` not found, read `/proc/cpuinfo` and count `processor :` lines
- [ ] Tests: verify on Debian + Alpine images

### Task 2.3 — Implement throttle(container, percentage, duration)
**File:** `CgroupsCpuChaos.java`  
**Effort:** 1h

- [ ] Start cpulimit as background process (same as existing `throttle()`)
- [ ] Launch a background shell that sleeps for duration then kills cpulimit:
  ```
  sh -c "cpulimit -l <pct> -p 1 & CPID=$!; sleep <seconds>; kill $CPID 2>/dev/null"
  ```
- [ ] All in one `execInContainer` call — no Java-side timer needed, fully container-internal
- [ ] Validate duration > 0
- [ ] Tests: verify cpulimit running, verify it stops after duration (use short duration like 3s in test)

### Task 2.4 — Implement stressWithThrottle()
**File:** `CgroupsCpuChaos.java`  
**Effort:** 1h

- [ ] Kill any existing stress-ng + cpulimit first (clean state)
- [ ] Start stress-ng with N workers as background process
- [ ] Wait briefly (100ms) for stress-ng to start
- [ ] Find stress-ng parent PID via `pgrep -o stress-ng` (oldest = parent)
- [ ] Start cpulimit targeting stress-ng parent PID (not PID 1!) with percentage cap
- [ ] This means: stress-ng generates load, cpulimit caps it — simulates Kubernetes CPU limits
- [ ] Reset kills both
- [ ] Tests: verify both processes running, verify CPU bounded

### Task 2.5 — Implement isThrottled() / isStressed()
**File:** `CgroupsCpuChaos.java`  
**Effort:** 30min

- [ ] `isThrottled()`: `pgrep cpulimit` — exit code 0 = running
- [ ] `isStressed()`: `pgrep stress-ng` — exit code 0 = running
- [ ] Handle container not running → return false (not throw)
- [ ] Tests: verify state before/after throttle/stress/reset

---

## Phase 3: Quality & Tests

### Task 3.1 — Unit Tests (Mock-based)
**File:** `CgroupsCpuChaosTest.java` (extend existing)  
**Effort:** 2h

- [ ] Test `getCurrentUsage()` with known `/proc/stat` values
- [ ] Test `getAvailableCores()` parsing
- [ ] Test `isThrottled()` / `isStressed()` state transitions
- [ ] Test `throttle(container, pct, duration)` parameter validation
- [ ] Test `stressWithThrottle()` parameter validation
- [ ] Test null parameter rejection on all new methods
- [ ] Test stopped container rejection on all new methods

### Task 3.2 — Integration Tests
**File:** `CgroupsCpuChaosComprehensiveTest.java` (extend existing)  
**Effort:** 2-3h

- [ ] `getCurrentUsage()` — stress 2 workers, verify usage > 0
- [ ] `getAvailableCores()` — verify returns > 0 on Debian + Alpine
- [ ] `throttle(container, 50, Duration.ofSeconds(3))` — verify cpulimit running, verify gone after ~4s
- [ ] `stressWithThrottle(container, 2, 50)` — verify both processes running
- [ ] `isThrottled()` / `isStressed()` — verify true during chaos, false after reset
- [ ] Combined scenario: stress → check usage → throttle → check usage → reset → check clean
- [ ] Alpine-specific: verify all tools install and work on `redis:7.4-alpine`

### Task 3.3 — Code Quality
**Effort:** 1h

- [ ] All methods < 40 lines
- [ ] Extract repeated `execInContainer` + error check into private helper if pattern repeats > 3x
- [ ] All public methods get full Javadoc (existing ones are bare)
- [ ] Package-info.java for `com.macstab.chaos.cpu`
- [ ] Verify 90%+ test coverage

---

## Summary

| Phase | Tasks | Effort | What |
|-------|-------|--------|------|
| 1 | 1.1–1.3 | 2-3h | Fix existing: @Slf4j, getCurrentUsage, ServiceLoader |
| 2 | 2.1–2.5 | 4h | New methods: cores, throttle+timeout, stress+throttle, state query |
| 3 | 3.1–3.3 | 5-6h | Tests + quality |
| **Total** | **11 tasks** | **~11-13h** | |

## Implementation Order

```
1.1 Fix basics  ──→  1.2 getCurrentUsage  ──→  1.3 ServiceLoader
                                                       │
         ┌─────────────────────────────────────────────┘
         ↓
2.1 SPI extension  ──→  2.2 getAvailableCores  ──→  2.3 throttle+duration
                                                             │
                                                    2.4 stressWithThrottle
                                                             │
                                                    2.5 isThrottled/isStressed
                                                             │
         ┌───────────────────────────────────────────────────┘
         ↓
3.1 Unit tests  ──→  3.2 Integration tests  ──→  3.3 Code quality
```

## Critical Notes

- `cpulimit` targets PID 1 for `throttle()` — works when PID 1 is `redis-server`. If container uses `tini` or `docker-init` as PID 1, cpulimit caps the init process which propagates to children. Verify this works in tests.
- `stress-ng --timeout 0` means "run forever" (not "don't run"). This is correct for the indefinite `stress()` overload.
- Alpine uses `apk add` not `apt install` — `PackageInstaller` handles this automatically via platform detection.
- `cpulimit` and `stress-ng` are userspace tools — no kernel module or special capability needed. Works in unprivileged containers.

## Rules

- **ASK before executing any code changes**
- **ASK before committing**
- All methods < 40 lines
- 90%+ test coverage
- Backwards compatible — existing `throttle(container, pct)` and `stress(container, workers)` signatures unchanged
