# Chaos Testing API Specification v1.0

**Language-Agnostic Specification for Cross-Platform Implementation**

---

## Overview

This specification defines the API surface for chaos testing across multiple programming languages (Java, Node.js, Go, Rust, C++, Dart).

**Core Principle:** Same concepts, adapted syntax per language idioms.

---

## Pattern Types

### 1. Ramp Pattern
Gradually increases/decreases value from start to end over duration.

**Curves:**
- `LINEAR` - Constant rate of change
- `EXPONENTIAL` - Slow start, fast end (x²)
- `LOGARITHMIC` - Fast start, slow end (√x)

**API:**
```
{chaos-type}().{operation}()
    .rampFrom({startValue})
    .to({endValue})
    .over({duration})
    .withCurve({curve})  // optional, default: LINEAR
    .execute()
```

### 2. Noise Pattern
Random fluctuations around baseline with specified amplitude.

**Distributions:**
- `GAUSSIAN` - Normal distribution (bell curve)
- `UNIFORM` - Equal probability within range

**Requirements:**
- MUST support seeded randomness for repeatability
- MUST clamp values to non-negative

**API:**
```
{chaos-type}().{operation}()
    .randomAround({baseline}, {amplitude})
    .withSeed({seed})  // optional
    .forDuration({duration})
    .execute()
```

### 3. Wave Pattern
Cyclic oscillation between min and max values.

**Wave Types:**
- `SINE` - Smooth oscillation (sin wave)
- `SQUARE` - Abrupt switches (square wave)
- `SAWTOOTH` - Linear ramp + reset (sawtooth wave)

**API:**
```
{chaos-type}().{operation}()
    .waveBetween({minValue}, {maxValue})
    .withPeriod({period})
    .withWaveType({type})  // optional, default: SINE
    .forDuration({duration})
    .execute()
```

### 4. Burst Pattern
Spike to high value, hold, then recover to baseline.

**API:**
```
{chaos-type}().{operation}()
    .burstFrom({baseline})
    .to({spikeValue})
    .holdFor({duration})
    .recoverOver({duration})
    .repeat({count})  // optional
    .execute()
```

---

## Chaos Operations

### CPU Chaos

**Operations:**
1. `throttle(percent)` - Limit CPU to percentage (uses cpulimit)
2. `stress(workers)` - Spawn CPU-bound workers (uses stress-ng)

**Implementation:**
- Tool: `cpulimit -l {percent} -p {pid}`
- Dynamic changes: Kill cpulimit, restart with new limit
- Auto-install: `stress-ng`, `cpulimit` (via package manager)

**API:**
```
chaos.cpu().throttle()
    .rampFrom(10).to(90).over(seconds(60))
    .execute()

chaos.cpu().stress(workers: 4)
    .randomAround(4, amplitude: 2).forDuration(minutes(5))
    .execute()
```

### Memory Chaos

**Operations:**
1. `stress(size)` - Allocate and write to memory
2. `pressure(threshold)` - Soft limit (not enforced, just suggested)

**Implementation:**
- Tool: `stress-ng --vm 1 --vm-bytes {size}`
- Dynamic changes: Kill stress-ng, restart with new size
- Container limit: Set via Docker API at creation

**API:**
```
chaos.memory().stress()
    .rampFrom("100M").to("1G").over(minutes(2))
    .execute()

chaos.memory().stress()
    .randomAround("500M", amplitude: "150M").forDuration(minutes(5))
    .execute()
```

### Disk Chaos

**Operations:**
1. `fill(path, percent)` - Fill disk to percentage
2. `stress(workers)` - Heavy I/O load

**Implementation:**
- Fill: `dd if=/dev/zero of={path}/load bs=1M count={size}`
- Stress: `stress-ng --hdd {workers}`
- Dynamic changes: Delete file, create new with different size

**API:**
```
chaos.disk().fill("/data")
    .waveBetween(20, 80).withPeriod(seconds(30)).forDuration(minutes(5))
    .execute()
```

### Network Chaos

**Operations:**
1. `injectLatency(delay)` - Add network delay
2. `injectPacketLoss(percent)` - Drop packets
3. `limitBandwidth(rate)` - Throttle bandwidth
4. `partitionFrom(target)` - Block traffic to target

**Implementation:**
- Tool: `tc qdisc add dev eth0 root netem delay {ms}ms`
- Tool: `tc qdisc add dev eth0 root netem loss {percent}%`
- Tool: `iptables -A OUTPUT -d {target} -j DROP`
- Requires: NET_ADMIN capability

**API:**
```
chaos.network().injectLatency()
    .rampFrom(millis(10)).to(millis(500)).over(seconds(30))
    .execute()
```

### Process Chaos

**Operations:**
1. `kill(name, signal)` - Send signal to process
2. `pause(name, duration)` - SIGSTOP → wait → SIGCONT
3. `limit(maxProcesses)` - Limit total processes

**Implementation:**
- Kill: `pkill -{signal} {name}`
- Pause: `pkill -STOP {name}; sleep {duration}; pkill -CONT {name}`

**API:**
```
chaos.process().kill("redis-server", SIGTERM)
chaos.process().pause("redis-server", seconds(10))
```

### Time Chaos

**Operations:**
1. `shift(offset)` - Shift clock forward/backward
2. `drift(multiplier)` - Speed up/slow down time

**Implementation:**
- Tool: `faketime` with LD_PRELOAD
- Modify /etc/profile or container environment

**API:**
```
chaos.time().shift(hours(2))  // 2 hours in future
chaos.time().drift(2.0)       // 2x speed
```

### DNS Chaos

**Operations:**
1. `blockResolution(hostname)` - Block DNS queries
2. `delayResolution(delay)` - Add DNS latency

**Implementation:**
- Block: `iptables -A OUTPUT -p udp --dport 53 -j DROP`
- Delay: `tc qdisc add dev eth0 root netem delay {ms}ms` (port 53 filter)
- Requires: NET_ADMIN capability

**API:**
```
chaos.dns().blockResolution("api.example.com")
chaos.dns().delayResolution(millis(500))
```

---

## Execution Model

### Pattern Execution
All patterns execute in background thread/goroutine/async task.

**Returns:** Execution handle with methods:
- `stop()` - Terminate pattern immediately
- `await()` - Block until completion
- `isRunning()` - Check if still executing

### Sample Interval
Patterns generate values at specified intervals (default: 100ms).

**Configurable:**
```
.withSampleInterval(millis(500))  // Change every 500ms
```

**Trade-off:**
- Fast (100ms): Smooth pattern, more overhead
- Slow (5s): Coarse pattern, less overhead

---

## Type Conversion

### Value Types per Chaos Operation

| Operation | Pattern Value Type | Converted To |
|-----------|-------------------|--------------|
| CPU throttle | Double (0-100) | Integer percent |
| CPU stress | Double (1-N) | Integer worker count |
| Memory stress | Double (1-N) | String with unit ("512M") |
| Disk fill | Double (0-100) | Integer percent |
| Network latency | Double (0-N) | Duration (milliseconds) |
| Process limit | Double (1-N) | Integer count |

### Converters (Language-Specific)

**Java:**
```java
ValueConverter<Integer> toInteger()  // Math.round()
ValueConverter<String> toMemoryMB()  // value + "M"
```

**Go:**
```go
func ToInt(v float64) int { return int(math.Round(v)) }
func ToMemoryMB(v float64) string { return fmt.Sprintf("%dM", int(v)) }
```

**Rust:**
```rust
fn to_int(v: f64) -> i32 { v.round() as i32 }
fn to_memory_mb(v: f64) -> String { format!("{}M", v.round() as u64) }
```

---

## Duration API

### Standard: ISO 8601 / Language Duration Types

**Java:** `java.time.Duration`
```java
Duration.ofSeconds(60)
Duration.ofMinutes(5)
```

**Go:** `time.Duration`
```go
60 * time.Second
5 * time.Minute
```

**Rust:** `std::time::Duration`
```rust
Duration::from_secs(60)
Duration::from_secs(5 * 60)
```

**Node.js:** Milliseconds or helper
```typescript
60_000  // 60 seconds
Duration.seconds(60)  // helper
```

---

## Container Requirements

### Docker API Calls (Universal)

All languages MUST use Docker exec to run tools inside containers:

```bash
# CPU throttle
docker exec <container> cpulimit -l 50 -p <pid>

# Memory stress
docker exec <container> stress-ng --vm 1 --vm-bytes 500M

# Network latency
docker exec <container> tc qdisc add dev eth0 root netem delay 100ms
```

### Required Capabilities

| Chaos Type | Capability | Required? |
|------------|-----------|-----------|
| CPU | None | No |
| Memory | None | No |
| Disk | None | No |
| Process | None | No |
| Time | None | No |
| Network | NET_ADMIN | **YES** |
| DNS | NET_ADMIN | **YES** |

### Container Setup (Java Example)

```java
GenericContainer<?> container = new GenericContainer<>("redis:7-alpine")
    .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
        .withCapAdd(Capability.NET_ADMIN)  // For network/DNS chaos
        .withMemory(512L * 1024 * 1024)     // Memory limit
        .withCpuCount(2L));                  // CPU count limit
```

---

## Error Handling

### Standard Exceptions

All implementations MUST throw these exception types:

1. `ChaosProviderNotFoundException` - Module not installed
2. `ChaosOperationFailedException` - Tool execution failed
3. `ChaosConfigurationException` - Invalid parameter
4. `ChaosUnsupportedOperationException` - Platform limitation

---

## Repeatability

### Seeded Random Patterns

All noise patterns MUST support seeds for deterministic chaos:

```
.withSeed(42)  // Same seed → same random sequence
```

**Implementation:** Use language-standard PRNG with seed.

---

## Platform Support

### Minimum Requirements

- **OS:** Linux (kernel 3.10+)
- **Container:** Docker Engine 20.10+ or Kubernetes 1.20+
- **Tools:** stress-ng, cpulimit, iptables, tc, faketime

### Platform Detection

```
chaos.cpu().isSupported()  // Check if platform supports CPU chaos
```

---

## Version

**Specification Version:** 1.0  
**Date:** 2026-03-22  
**Author:** Christian Schnapka / Macstab GmbH  
**License:** MIT

---

## Reference Implementation

**Java:** https://github.com/macstab/chaos-testing

**Ports (Planned):**
- Node.js (2026 Q3)
- Go (2026 Q4)
- Rust (2027 Q1)
- C++ (2027 Q2)
- Dart (2027 Q3)
