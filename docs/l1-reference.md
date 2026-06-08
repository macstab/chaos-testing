# L1 Annotation Reference

L1 annotations inject a single syscall-level failure primitive. They are the building blocks
from which L2 composites and L3 incident scenarios are constructed. Use L1 directly when you
need precise control over exactly which syscall fails and how.

All L1 annotations support:
- **`id`** — container filter; empty string targets all containers
- **`onMissingEnv`** — `ERROR` (default, fails the test) or `ABORT` (skips the test as YELLOW in CI)
- **`@Repeatable`** — multiple L1s can stack on the same test class or method
- **Class or method scope** — applied at `@BeforeAll` (class) or `@BeforeEach` (method)

---

## Memory (`macstab-chaos-memory`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-memory:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-memory</artifactId>`

Requires `@SyscallLevelChaos(LibchaosLib.MEMORY)` on the container definition. Loads
`libchaos-memory.so` via `LD_PRELOAD`, interposing `mmap`, `munmap`, `mprotect`, and `madvise`
at the dynamic-linker level.

### `mmap` (all mappings)

#### `@ChaosMmapEnomem`
Injects `ENOMEM` into all `mmap` calls (anonymous and file-backed), simulating virtual-address-space
or RAM+swap exhaustion on any memory-mapping operation.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `probability` | `double` | `1.0` | Fraction of matching calls that fail (0.0–1.0] |
| `id` | `String` | `""` | Container filter; empty = all containers |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-memory is unavailable |

#### `@ChaosMmapEacces`
Injects `EACCES` into all `mmap` calls, simulating a permission denial on memory mapping.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `probability` | `double` | `1.0` | Fraction of matching calls that fail |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-memory is unavailable |

#### `@ChaosMmapEagain`
Injects `EAGAIN` into all `mmap` calls, simulating transient unavailability of mapping resources.

#### `@ChaosMmapEbadf`
Injects `EBADF` into all `mmap` calls, simulating a bad file descriptor passed to `mmap`.

#### `@ChaosMmapEfault`
Injects `EFAULT` into all `mmap` calls, simulating a bad address in the mapping arguments.

#### `@ChaosMmapEinval`
Injects `EINVAL` into all `mmap` calls, simulating invalid arguments (bad flags, alignment, or length).

#### `@ChaosMmapEmfile`
Injects `EMFILE` into all `mmap` calls, simulating per-process file-mapping limit exhaustion.

#### `@ChaosMmapEnfile`
Injects `ENFILE` into all `mmap` calls, simulating system-wide file-mapping limit exhaustion.

#### `@ChaosMmapEnodev`
Injects `ENODEV` into all `mmap` calls, simulating a device that does not support memory mapping.

#### `@ChaosMmapEperm`
Injects `EPERM` into all `mmap` calls, simulating a capability or SELinux policy denial.

#### `@ChaosMmapLatency`
Adds `delayMs` milliseconds of latency before every `mmap` call (anonymous and file-backed);
the mapping succeeds but takes longer than expected.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `delayMs` | `long` | `50` | Delay to inject before each matching call, in milliseconds |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-memory is unavailable |

---

### `mmap_anon` (anonymous mappings only)

Same errno variants and latency as `mmap`, but restricted to anonymous `mmap` calls
(heap allocator path). Annotations: `@ChaosMmapAnonEacces`, `@ChaosMmapAnonEagain`,
`@ChaosMmapAnonEbadf`, `@ChaosMmapAnonEfault`, `@ChaosMmapAnonEinval`, `@ChaosMmapAnonEmfile`,
`@ChaosMmapAnonEnfile`, `@ChaosMmapAnonEnodev`, `@ChaosMmapAnonEnomem`, `@ChaosMmapAnonEperm`,
`@ChaosMmapAnonLatency`.

Attributes are identical to the `mmap` equivalents.

---

### `mmap_file` (file-backed mappings only)

Same errno variants and latency as `mmap`, but restricted to file-backed `mmap` calls (I/O path).
Annotations: `@ChaosMmapFileEacces`, `@ChaosMmapFileEagain`, `@ChaosMmapFileEbadf`,
`@ChaosMmapFileEfault`, `@ChaosMmapFileEinval`, `@ChaosMmapFileEmfile`, `@ChaosMmapFileEnfile`,
`@ChaosMmapFileEnodev`, `@ChaosMmapFileEnomem`, `@ChaosMmapFileEperm`, `@ChaosMmapFileLatency`.

Attributes are identical to the `mmap` equivalents.

---

### `munmap`

#### `@ChaosMunmapEfault`
Injects `EFAULT` into `munmap` calls, simulating a bad address on unmapping.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `probability` | `double` | `1.0` | Fraction of matching calls that fail |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-memory is unavailable |

#### `@ChaosMunmapEinval`
Injects `EINVAL` into `munmap` calls, simulating invalid address or length arguments.

#### `@ChaosMunmapLatency`
Adds `delayMs` milliseconds of latency before every `munmap` call.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `delayMs` | `long` | `50` | Delay in milliseconds |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-memory is unavailable |

---

### `mprotect`

#### `@ChaosMprotectEacces`
Injects `EACCES` into `mprotect` calls, simulating a permission denial when changing memory protection.

#### `@ChaosMprotectEfault`
Injects `EFAULT` into `mprotect` calls, simulating a bad address.

#### `@ChaosMprotectEinval`
Injects `EINVAL` into `mprotect` calls, simulating invalid protection flags.

#### `@ChaosMprotectEnomem`
Injects `ENOMEM` into `mprotect` calls, simulating kernel inability to allocate page table entries.

All `mprotect` errno variants share:

| Attribute | Type | Default | Description |
|---|---|---|---|
| `probability` | `double` | `1.0` | Fraction of matching calls that fail |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-memory is unavailable |

#### `@ChaosMprotectLatency`
Adds `delayMs` milliseconds of latency before every `mprotect` call.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `delayMs` | `long` | `50` | Delay in milliseconds |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-memory is unavailable |

---

### `madvise`

#### `@ChaosMadviseEacces`
Injects `EACCES` into `madvise` calls, simulating a page-protection conflict with the advice given.

#### `@ChaosMadviseEagain`
Injects `EAGAIN` into `madvise` calls, simulating kernel memory pressure preventing the operation.

#### `@ChaosMadviseEbadf`
Injects `EBADF` into `madvise` calls, simulating a file-backed region whose backing file was closed.

#### `@ChaosMadviseEfault`
Injects `EFAULT` into `madvise` calls, simulating a bad address or unmapped region.

#### `@ChaosMadviseEinval`
Injects `EINVAL` into `madvise` calls, simulating an invalid advice value or unaligned address.

#### `@ChaosMadviseEnosys`
Injects `ENOSYS` into `madvise` calls, simulating a kernel that does not implement the given advice.

#### `@ChaosMadviseEnomem`
Injects `ENOMEM` into `madvise` calls, simulating the range not being fully mapped.

#### `@ChaosMadviseEperm`
Injects `EPERM` into `madvise` calls, simulating a capability or seal violation.

All `madvise` errno variants share:

| Attribute | Type | Default | Description |
|---|---|---|---|
| `probability` | `double` | `1.0` | Fraction of matching calls that fail |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-memory is unavailable |

#### `@ChaosMadviseLatency`
Adds `delayMs` milliseconds of latency before every `madvise` call.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `delayMs` | `long` | `50` | Delay in milliseconds |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-memory is unavailable |

---

### Memory wildcard (all intercepted memory syscalls)

#### `@ChaosWildcardEinval` *(memory)*
Injects `EINVAL` into every intercepted memory syscall (`mmap`, `munmap`, `mprotect`, `madvise`).

#### `@ChaosWildcardLatency` *(memory)*
Adds `delayMs` milliseconds of latency before every intercepted memory syscall.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `delayMs` | `long` | `50` | Delay in milliseconds |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-memory is unavailable |

---

## Time (`macstab-chaos-time`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-time:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-time</artifactId>`

Requires `@SyscallLevelChaos(LibchaosLib.TIME)` on the container definition. Loads
`libchaos-time.so` via `LD_PRELOAD`, interposing `clock_gettime`, `nanosleep`, and `usleep`.

### `clock_gettime`

#### `@ChaosClockGettimeOffset`
Adds a signed `deltaMs` millisecond offset to every `clock_gettime(2)` result, causing the caller
to observe a clock that is consistently ahead of or behind wall time. The call succeeds; only the
returned `timespec` is modified.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `deltaMs` | `long` | `-60000` | Clock shift in milliseconds; negative = rewind, positive = advance |
| `probability` | `double` | `1.0` | Probability the offset fires when matched |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-time is unavailable |

#### `@ChaosClockGettimeLatency`
Delays every `clock_gettime(2)` call by `delayMs` milliseconds before delegating to the real kernel
call; the correct time is returned after the delay.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `delayMs` | `long` | `10` | Delay in milliseconds |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-time is unavailable |

#### `@ChaosClockGettimeEagain`
Injects `EAGAIN` into `clock_gettime(2)`, simulating a temporarily unavailable clock resource.

#### `@ChaosClockGettimeEfault`
Injects `EFAULT` into `clock_gettime(2)`, simulating a bad pointer for the `timespec` output.

#### `@ChaosClockGettimeEintr`
Injects `EINTR` into `clock_gettime(2)`, simulating signal delivery interrupting the clock read.

#### `@ChaosClockGettimeEinval`
Injects `EINVAL` into `clock_gettime(2)`, simulating an unknown or unsupported clock identifier.

#### `@ChaosClockGettimeEnosys`
Injects `ENOSYS` into `clock_gettime(2)`, simulating a kernel that does not expose the syscall.

#### `@ChaosClockGettimeEperm`
Injects `EPERM` into `clock_gettime(2)`, simulating a capability denial for privileged clock ids.

All `clock_gettime` errno variants share:

| Attribute | Type | Default | Description |
|---|---|---|---|
| `probability` | `double` | `1.0` | Fraction of matching calls that fail |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-time is unavailable |

---

### `nanosleep`

#### `@ChaosNanosleepLatency`
Adds extra `delayMs` milliseconds of latency before every `nanosleep(2)` call; the sleep completes
after the injected delay plus the requested sleep duration.

#### `@ChaosNanosleepEagain`
Injects `EAGAIN` into `nanosleep(2)`.

#### `@ChaosNanosleepEfault`
Injects `EFAULT` into `nanosleep(2)`, simulating a bad pointer for the `timespec` argument.

#### `@ChaosNanosleepEintr`
Injects `EINTR` into `nanosleep(2)`, simulating signal interruption of a sleep.

#### `@ChaosNanosleepEinval`
Injects `EINVAL` into `nanosleep(2)`, simulating a negative or out-of-range sleep duration.

#### `@ChaosNanosleepEnosys`
Injects `ENOSYS` into `nanosleep(2)`.

#### `@ChaosNanosleepEperm`
Injects `EPERM` into `nanosleep(2)`.

All `nanosleep` variants share the same attribute set as `clock_gettime` variants above (errno
variants: `probability`, `id`, `onMissingEnv`; latency variant: `delayMs`, `id`, `onMissingEnv`).

---

### `usleep`

#### `@ChaosUsleepLatency`
Adds extra `delayMs` milliseconds of latency before every `usleep(3)` call.

#### `@ChaosUsleepEagain`, `@ChaosUsleepEfault`, `@ChaosUsleepEintr`, `@ChaosUsleepEinval`, `@ChaosUsleepEnosys`, `@ChaosUsleepEperm`
Inject the corresponding errno into `usleep(3)`. Same attribute set as the `nanosleep` errno variants.

---

### Time wildcard (all intercepted time syscalls)

#### `@ChaosWildcardEagain` *(time)*
#### `@ChaosWildcardEfault` *(time)*
#### `@ChaosWildcardEintr` *(time)*
#### `@ChaosWildcardEinval` *(time)*
#### `@ChaosWildcardEnosys` *(time)*
#### `@ChaosWildcardEperm` *(time)*
Inject the corresponding errno into every intercepted time syscall (`clock_gettime`, `nanosleep`, `usleep`).

#### `@ChaosWildcardLatency` *(time)*
Adds `delayMs` milliseconds of latency before every intercepted time syscall.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `delayMs` | `long` | `10` | Delay in milliseconds |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-time is unavailable |

---

## DNS (`macstab-chaos-dns`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-dns:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-dns</artifactId>`

Requires `@SyscallLevelChaos(LibchaosLib.DNS)` on the container definition. Loads
`libchaos-dns.so` via `LD_PRELOAD`, interposing `getaddrinfo(3)` (forward lookups) and
`getnameinfo(3)` (reverse lookups).

DNS errno variants inject EAI error codes rather than POSIX `errno` values. EAI latency variants
always delegate to the real resolver after the injected delay.

### `forward` (getaddrinfo — forward DNS lookups)

#### `@ChaosForwardEainoname`
Injects `EAI_NONAME` into every `getaddrinfo` call, causing resolution to fail as if the hostname
does not exist in the DNS namespace (NXDOMAIN-equivalent). No `probability` — always fires.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-dns is unavailable |

#### `@ChaosForwardEaiagain`
Injects `EAI_AGAIN` into every `getaddrinfo` call, simulating a transient DNS server failure or
network-level resolver timeout.

#### `@ChaosForwardEaifail`
Injects `EAI_FAIL` into every `getaddrinfo` call, simulating a permanent non-recoverable DNS
infrastructure failure (SERVFAIL-equivalent).

#### `@ChaosForwardEaimemory`
Injects `EAI_MEMORY` into every `getaddrinfo` call, simulating out-of-memory in the resolver
library.

#### `@ChaosForwardEaisystem`
Injects `EAI_SYSTEM` into every `getaddrinfo` call, simulating a system-level error (errno is set).

All `forward` EAI errno variants share:

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-dns is unavailable |

#### `@ChaosForwardLatency`
Delays every `getaddrinfo` call by an additional `delayMs` milliseconds before delegating to the
real resolver, simulating slow DNS caused by overloaded nameservers or dropped UDP packets.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `delayMs` | `long` | `100` | Extra latency added to each forward lookup, in milliseconds |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-dns is unavailable |

---

### `reverse` (getnameinfo — reverse DNS lookups)

Same variants as `forward`, targeting `getnameinfo(3)`:
`@ChaosReverseEainoname`, `@ChaosReverseEaiagain`, `@ChaosReverseEaifail`,
`@ChaosReverseEaimemory`, `@ChaosReverseEaisystem`, `@ChaosReverseLatency`.

Attributes are identical to their `forward` counterparts.

---

### DNS wildcard (all intercepted DNS calls)

`@ChaosWildcardEainoname`, `@ChaosWildcardEaiagain`, `@ChaosWildcardEaifail`,
`@ChaosWildcardEaimemory`, `@ChaosWildcardEaisystem`, `@ChaosWildcardLatency` *(dns)* —
inject the corresponding failure into both forward and reverse DNS calls simultaneously.

---

## Network/Connection (`macstab-chaos-connection`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-connection:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-connection</artifactId>`

Requires `@SyscallLevelChaos(LibchaosLib.NET)` on the container definition. Loads
`libchaos-net.so` via `LD_PRELOAD`, interposing `connect`, `accept`, `socket`, `bind`, `listen`,
`shutdown`, `send`, `recv`, and `poll`.

Connection errno and latency variants use `toxicity` (not `probability`) as the per-call
Bernoulli parameter.

### `connect`

#### `@ChaosConnectEconnrefused`
Injects `ECONNREFUSED` into `connect(2)`, simulating an immediate TCP RST (server is reachable but
not listening on the target port).

| Attribute | Type | Default | Description |
|---|---|---|---|
| `toxicity` | `double` | `1.0` | Per-call probability of failure (0.0–1.0] |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-net is unavailable |

#### `@ChaosConnectEtimedout`
Injects `ETIMEDOUT` into `connect(2)`, simulating a connection attempt that receives no response
(server unreachable or black-holed).

#### `@ChaosConnectEhostunreach`
Injects `EHOSTUNREACH` into `connect(2)`, simulating a routing failure where the target host is
not reachable.

#### `@ChaosConnectEnetunreach`
Injects `ENETUNREACH` into `connect(2)`, simulating a routing failure where no route exists to the
target network.

#### `@ChaosConnectEagain`
Injects `EAGAIN` into `connect(2)`, simulating transient resource exhaustion on the connecting side.

#### `@ChaosConnectEconnreset`
Injects `ECONNRESET` into `connect(2)`, simulating a RST received during the handshake.

All `connect` errno variants share:

| Attribute | Type | Default | Description |
|---|---|---|---|
| `toxicity` | `double` | `1.0` | Per-call probability of failure |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-net is unavailable |

#### `@ChaosConnectLatency`
Delays every `connect(2)` call by `delayMs` milliseconds before delegating to the real kernel call;
the connection succeeds after the delay.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `delayMs` | `long` | `100` | Delay in milliseconds |
| `toxicity` | `double` | `1.0` | Per-call probability the delay fires |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-net is unavailable |

---

### `accept`

#### `@ChaosAcceptEagain`
Injects `EAGAIN` into `accept(2)`, simulating no pending connections (non-blocking socket).

#### `@ChaosAcceptEconnreset`
Injects `ECONNRESET` into `accept(2)`, simulating the peer resetting the connection before accept.

#### `@ChaosAcceptEinval`
Injects `EINVAL` into `accept(2)`, simulating a socket that is not listening.

#### `@ChaosAcceptEmfile`
Injects `EMFILE` into `accept(2)`, simulating per-process file-descriptor exhaustion.

#### `@ChaosAcceptEnfile`
Injects `ENFILE` into `accept(2)`, simulating system-wide file-descriptor exhaustion.

All `accept` errno variants share the same attributes as `connect` errno variants.

#### `@ChaosAcceptLatency`
Delays every `accept(2)` call by `delayMs` milliseconds.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `delayMs` | `long` | `100` | Delay in milliseconds |
| `toxicity` | `double` | `1.0` | Per-call probability the delay fires |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-net is unavailable |

---

### `socket`

#### `@ChaosSocketEafnosupport`
Injects `EAFNOSUPPORT` into `socket(2)`, simulating an unsupported address family.

#### `@ChaosSocketEmfile`
Injects `EMFILE` into `socket(2)`, simulating per-process file-descriptor exhaustion on socket creation.

#### `@ChaosSocketEnfile`
Injects `ENFILE` into `socket(2)`, simulating system-wide file-descriptor exhaustion.

#### `@ChaosSocketEnomem`
Injects `ENOMEM` into `socket(2)`, simulating kernel memory exhaustion during socket allocation.

#### `@ChaosSocketEprotonosupport`
Injects `EPROTONOSUPPORT` into `socket(2)`, simulating an unsupported protocol type.

All `socket` errno variants share the same attributes as `connect` errno variants.

#### `@ChaosSocketLatency`
Delays every `socket(2)` call by `delayMs` milliseconds.

---

### `bind`

#### `@ChaosBindEaddrinuse`
Injects `EADDRINUSE` into `bind(2)`, simulating a port that is already bound by another process.

#### `@ChaosBindEaddrnotavail`
Injects `EADDRNOTAVAIL` into `bind(2)`, simulating an address that is not available on this host.

#### `@ChaosBindEinval`
Injects `EINVAL` into `bind(2)`, simulating an already-bound socket or invalid address.

#### `@ChaosBindEnomem`
Injects `ENOMEM` into `bind(2)`, simulating kernel memory exhaustion during bind.

All `bind` errno variants share the same attributes as `connect` errno variants.

#### `@ChaosBindLatency`
Delays every `bind(2)` call by `delayMs` milliseconds.

---

### `listen`

#### `@ChaosListenEaddrinuse`
Injects `EADDRINUSE` into `listen(2)`, simulating another socket already listening on the same port.

#### `@ChaosListenEinval`
Injects `EINVAL` into `listen(2)`, simulating an unbound or already-connected socket.

#### `@ChaosListenEopnotsupp`
Injects `EOPNOTSUPP` into `listen(2)`, simulating a socket type that does not support listening.

All `listen` errno variants share the same attributes as `connect` errno variants.

#### `@ChaosListenLatency`
Delays every `listen(2)` call by `delayMs` milliseconds.

---

### `shutdown`

#### `@ChaosShutdownEinval`
Injects `EINVAL` into `shutdown(2)`, simulating an invalid shutdown direction argument.

#### `@ChaosShutdownEnotconn`
Injects `ENOTCONN` into `shutdown(2)`, simulating a shutdown on a socket that is not connected.

All `shutdown` errno variants share the same attributes as `connect` errno variants.

#### `@ChaosShutdownLatency`
Delays every `shutdown(2)` call by `delayMs` milliseconds.

---

### `send`

#### `@ChaosSendEagain`
Injects `EAGAIN` into `send(2)`, simulating a full socket send buffer (non-blocking socket).

#### `@ChaosSendEconnreset`
Injects `ECONNRESET` into `send(2)`, simulating a RST received while sending.

#### `@ChaosSendEnobufs`
Injects `ENOBUFS` into `send(2)`, simulating kernel buffer exhaustion.

#### `@ChaosSendEnomem`
Injects `ENOMEM` into `send(2)`, simulating kernel memory exhaustion during send.

#### `@ChaosSendEpipe`
Injects `EPIPE` into `send(2)`, simulating writing to a connection whose read end has been closed.

#### `@ChaosSendEtimedout`
Injects `ETIMEDOUT` into `send(2)`, simulating a send timeout expiry.

All `send` errno variants share the same attributes as `connect` errno variants.

#### `@ChaosSendLatency`
Delays every `send(2)` call by `delayMs` milliseconds.

---

### `recv`

#### `@ChaosRecvEagain`
Injects `EAGAIN` into `recv(2)`, simulating no data available (non-blocking socket).

#### `@ChaosRecvEconnreset`
Injects `ECONNRESET` into `recv(2)`, simulating a RST received while receiving.

#### `@ChaosRecvEintr`
Injects `EINTR` into `recv(2)`, simulating signal delivery interrupting a receive.

#### `@ChaosRecvEnobufs`
Injects `ENOBUFS` into `recv(2)`, simulating receive buffer exhaustion.

#### `@ChaosRecvEtimedout`
Injects `ETIMEDOUT` into `recv(2)`, simulating a receive timeout expiry.

All `recv` errno variants share the same attributes as `connect` errno variants.

#### `@ChaosRecvLatency`
Delays every `recv(2)` call by `delayMs` milliseconds.

#### `@ChaosRecvCorrupt`
Corrupts bytes in the buffer returned by each intercepted `recv(2)` call, flipping random bits at
a per-byte probability of `rate`, simulating bit errors that pass the TCP checksum.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `rate` | `double` | `0.001` | Per-byte bit-flip probability when the rule fires |
| `toxicity` | `double` | `1.0` | Per-call probability that any corruption is applied |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-net is unavailable |

---

### `poll`

#### `@ChaosPollLatency`
Delays every `poll(2)` call by `delayMs` milliseconds before delegating to the kernel.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `delayMs` | `long` | `100` | Delay in milliseconds |
| `toxicity` | `double` | `1.0` | Per-call probability the delay fires |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-net is unavailable |

#### `@ChaosPollTimeout`
Overrides the `timeout` argument of every intercepted `poll(2)` call with `timeoutMs`,
causing the call to return `0` (no events) after at most `timeoutMs` milliseconds even when the
application passed a longer or infinite timeout.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `timeoutMs` | `long` | `5000` | Enforced poll timeout, in milliseconds (strictly positive) |
| `toxicity` | `double` | `1.0` | Per-call probability the override fires |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-net is unavailable |

---

## Filesystem (`macstab-chaos-filesystem`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-filesystem:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-filesystem</artifactId>`

Requires `@SyscallLevelChaos(LibchaosLib.IO)` on the container definition. Loads
`libchaos-io.so` via `LD_PRELOAD`, interposing `open`, `read`, `write`, `close`, `fsync`,
`fdatasync`, `pread`, `pwrite`, `truncate`, `fallocate`, `unlink`, and `rename`.

### `open`

#### `@ChaosOpenEacces`
Injects `EACCES` into `open(2)`, simulating a permission denial when opening a file.

#### `@ChaosOpenEmfile`
Injects `EMFILE` into `open(2)`, simulating per-process file-descriptor exhaustion.

#### `@ChaosOpenEnfile`
Injects `ENFILE` into `open(2)`, simulating system-wide file-descriptor exhaustion.

#### `@ChaosOpenEnoent`
Injects `ENOENT` into `open(2)`, simulating a missing file or path component.

#### `@ChaosOpenEnospc`
Injects `ENOSPC` into `open(2)`, simulating no space left on the device when creating a file.

#### `@ChaosOpenErofs`
Injects `EROFS` into `open(2)`, simulating a write attempt on a read-only filesystem.

All `open` errno variants share:

| Attribute | Type | Default | Description |
|---|---|---|---|
| `probability` | `double` | `1.0` | Fraction of matching calls that fail |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-io is unavailable |

#### `@ChaosOpenLatency`
Delays every `open(2)` call by `delayMs` milliseconds; the file is opened normally after the delay.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `delayMs` | `long` | `50` | Delay in milliseconds |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-io is unavailable |

---

### `read`

#### `@ChaosReadEio`
Injects `EIO` into `read(2)`, simulating a hardware I/O error on the read path.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `probability` | `double` | `1.0` | Fraction of matching calls that fail |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-io is unavailable |

#### `@ChaosReadLatency`
Delays every `read(2)` call by `delayMs` milliseconds.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `delayMs` | `long` | `50` | Delay in milliseconds |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-io is unavailable |

#### `@ChaosReadCorrupt`
Corrupts bytes in the buffer returned by each `read(2)` call, flipping random bits at a per-byte
probability of `probability`, simulating silent data corruption from failing storage hardware.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `probability` | `double` | `0.001` | Per-byte bit-flip probability |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-io is unavailable |

---

### `write`

#### `@ChaosWriteEdquot`
Injects `EDQUOT` into `write(2)`, simulating disk quota exhaustion.

#### `@ChaosWriteEio`
Injects `EIO` into `write(2)`, simulating a hardware I/O error on the write path.

#### `@ChaosWriteEnospc`
Injects `ENOSPC` into `write(2)`, simulating no space left on the device.

#### `@ChaosWriteErofs`
Injects `EROFS` into `write(2)`, simulating a write attempt on a read-only filesystem.

All `write` errno variants share:

| Attribute | Type | Default | Description |
|---|---|---|---|
| `probability` | `double` | `1.0` | Fraction of matching calls that fail |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-io is unavailable |

#### `@ChaosWriteLatency`
Delays every `write(2)` call by `delayMs` milliseconds.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `delayMs` | `long` | `50` | Delay in milliseconds |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-io is unavailable |

#### `@ChaosWriteTorn`
Simulates a torn `write(2)` by intercepting the call, performing only a partial write of the
caller's buffer, and returning the partial byte count — as POSIX permits for writes larger than
`PIPE_BUF`.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `probability` | `double` | `0.001` | Per-write probability of tearing |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-io is unavailable |

---

### `close`

#### `@ChaosCloseEio`
Injects `EIO` into `close(2)`, simulating an I/O error flushing the last write buffer on close.

#### `@ChaosCloseLatency`
Delays every `close(2)` call by `delayMs` milliseconds.

---

### `fsync`

#### `@ChaosFsyncEio`
Injects `EIO` into `fsync(2)`, simulating an I/O error during flush to durable storage.

#### `@ChaosFsyncEnospc`
Injects `ENOSPC` into `fsync(2)`, simulating no space left when flushing buffered writes.

#### `@ChaosFsyncLatency`
Delays every `fsync(2)` call by `delayMs` milliseconds, simulating a slow storage flush.

---

### `fdatasync`

#### `@ChaosFdatasyncEio`
Injects `EIO` into `fdatasync(2)`, simulating an I/O error during data-only flush.

#### `@ChaosFdatasyncEnospc`
Injects `ENOSPC` into `fdatasync(2)`.

#### `@ChaosFdatasyncLatency`
Delays every `fdatasync(2)` call by `delayMs` milliseconds.

---

### `pread`

#### `@ChaosPreadEio`
Injects `EIO` into `pread(2)`, simulating an I/O error on a positional read.

#### `@ChaosPreadLatency`
Delays every `pread(2)` call by `delayMs` milliseconds.

#### `@ChaosPreadCorrupt`
Corrupts bytes returned by `pread(2)` at a per-byte bit-flip probability, identical in behaviour to
`@ChaosReadCorrupt` but targeting the positional-read syscall.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `probability` | `double` | `0.001` | Per-byte bit-flip probability |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-io is unavailable |

---

### `pwrite`

#### `@ChaosPwriteEdquot`
Injects `EDQUOT` into `pwrite(2)`.

#### `@ChaosPwriteEio`
Injects `EIO` into `pwrite(2)`.

#### `@ChaosPwriteEnospc`
Injects `ENOSPC` into `pwrite(2)`.

#### `@ChaosPwriteLatency`
Delays every `pwrite(2)` call by `delayMs` milliseconds.

#### `@ChaosPwriteTorn`
Simulates a torn `pwrite(2)` by returning a partial write count, identical in behaviour to
`@ChaosWriteTorn` but targeting the positional-write syscall.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `probability` | `double` | `0.001` | Per-write probability of tearing |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when libchaos-io is unavailable |

---

### `truncate`

#### `@ChaosTruncateEacces`
Injects `EACCES` into `truncate(2)`, simulating a permission denial when truncating a file.

#### `@ChaosTruncateEnoent`
Injects `ENOENT` into `truncate(2)`, simulating a missing target file.

#### `@ChaosTruncateEnospc`
Injects `ENOSPC` into `truncate(2)`, simulating no space when extending a file via truncate.

#### `@ChaosTruncateErofs`
Injects `EROFS` into `truncate(2)`, simulating a read-only filesystem.

#### `@ChaosTruncateLatency`
Delays every `truncate(2)` call by `delayMs` milliseconds.

---

### `unlink`

#### `@ChaosUnlinkEacces`
Injects `EACCES` into `unlink(2)`, simulating a permission denial when deleting a file.

#### `@ChaosUnlinkEnoent`
Injects `ENOENT` into `unlink(2)`, simulating deletion of a file that does not exist.

#### `@ChaosUnlinkErofs`
Injects `EROFS` into `unlink(2)`, simulating an attempt to delete from a read-only filesystem.

#### `@ChaosUnlinkLatency`
Delays every `unlink(2)` call by `delayMs` milliseconds.

---

### `rename_from` / `rename_to`

#### `@ChaosRenameFromEacces`, `@ChaosRenameFromEnoent`, `@ChaosRenameFromErofs`
Inject the corresponding errno into `rename(2)` when the source path is being evaluated.

#### `@ChaosRenameFromLatency`
Delays every `rename(2)` call (evaluated at the source path phase) by `delayMs` milliseconds.

#### `@ChaosRenameToEacces`, `@ChaosRenameToEnospc`, `@ChaosRenameToErofs`
Inject the corresponding errno into `rename(2)` when the destination path is being evaluated.

#### `@ChaosRenameToLatency`
Delays every `rename(2)` call (evaluated at the destination path phase) by `delayMs` milliseconds.

---

### `allocate` (fallocate)

#### `@ChaosAllocateEdquot`
Injects `EDQUOT` into `fallocate(2)`, simulating disk quota exhaustion during pre-allocation.

#### `@ChaosAllocateEio`
Injects `EIO` into `fallocate(2)`, simulating an I/O error during space pre-allocation.

#### `@ChaosAllocateEnospc`
Injects `ENOSPC` into `fallocate(2)`, simulating no space available for pre-allocation.

#### `@ChaosAllocateLatency`
Delays every `fallocate(2)` call by `delayMs` milliseconds.

---

## Network (`macstab-chaos-network`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-network:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-network</artifactId>`

The network module provides programmatic traffic-control chaos via `TcNetworkChaos` (backed by
Linux `tc` and `iptables`). It does **not** expose L1 annotation primitives — instead, use the
`NetworkChaos` API directly in test setup/teardown. Requires `NET_ADMIN` capability on the target
container.

This module provides one utility annotation:

#### `@DisabledOnNonLinuxHost`
JUnit 5 execution condition that disables tests on non-Linux host operating systems (macOS, Windows)
while enabling them inside any container or on a Linux host. Useful for tests that rely on native
Docker host-networking features unavailable under Docker Desktop.

| Attribute | Type | Default | Description |
|---|---|---|---|
| *(none)* | — | — | Marker annotation; no configurable attributes |

---

## Process (`macstab-chaos-process`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-process:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-process</artifactId>`

Requires `@SyscallLevelChaos(LibchaosLib.PROCESS)` on the container definition. Loads
`libchaos-process.so` via `LD_PRELOAD`, interposing `execve`, `execveat`, `fork`,
`posix_spawn`, `posix_spawnp`, `pthread_create`, and `waitpid`.

Process annotations have two families per syscall: immediate-fire variants (using `probability`)
and fail-after variants (using `failAfterCount` — fail on the N-th call and all subsequent calls),
plus latency variants.

**Common attribute sets:**

- **Errno variants:** `probability` (`double`, default `1.0`), `id` (`String`, default `""`), `onMissingEnv` (`OnMissingEnv`, default `ERROR`)
- **FailAfter variants:** `failAfterCount` (`int`, default `1`), `id` (`String`, default `""`), `onMissingEnv` (`OnMissingEnv`, default `ERROR`)
- **Latency variants:** `delayMs` (`long`, default `100`), `id` (`String`, default `""`), `onMissingEnv` (`OnMissingEnv`, default `ERROR`)

---

### `execve`

Intercepts `execve(2)` — the primary syscall used to replace the current process image with a new
executable.

| Annotation | Effect |
|---|---|
| `@ChaosExecveE2big` | Injects `E2BIG` — argument list too long |
| `@ChaosExecveEacces` | Injects `EACCES` — execute permission denied |
| `@ChaosExecveEmfile` | Injects `EMFILE` — file-descriptor limit exceeded |
| `@ChaosExecveEnfile` | Injects `ENFILE` — system-wide file-descriptor limit exceeded |
| `@ChaosExecveEnoent` | Injects `ENOENT` — executable not found |
| `@ChaosExecveEnomem` | Injects `ENOMEM` — kernel out of memory for exec |
| `@ChaosExecveEperm` | Injects `EPERM` — insufficient privilege |
| `@ChaosExecveE2bigFailAfter` | Injects `E2BIG` starting from the N-th `execve` call |
| `@ChaosExecveEaccesFailAfter` | Injects `EACCES` starting from the N-th call |
| `@ChaosExecveEmfileFailAfter` | Injects `EMFILE` starting from the N-th call |
| `@ChaosExecveEnfileFailAfter` | Injects `ENFILE` starting from the N-th call |
| `@ChaosExecveEnoentFailAfter` | Injects `ENOENT` starting from the N-th call |
| `@ChaosExecveEnomemFailAfter` | Injects `ENOMEM` starting from the N-th call |
| `@ChaosExecveEpermFailAfter` | Injects `EPERM` starting from the N-th call |
| `@ChaosExecveLatency` | Delays every `execve` call by `delayMs` milliseconds |

---

### `execveat`

Intercepts `execveat(2)` — the directory-relative variant of `execve`. Same variants as `execve`:

| Annotation | Effect |
|---|---|
| `@ChaosExecveatE2big` | Injects `E2BIG` |
| `@ChaosExecveatEacces` | Injects `EACCES` |
| `@ChaosExecveatEmfile` | Injects `EMFILE` |
| `@ChaosExecveatEnfile` | Injects `ENFILE` |
| `@ChaosExecveatEnoent` | Injects `ENOENT` |
| `@ChaosExecveatEnomem` | Injects `ENOMEM` |
| `@ChaosExecveatEperm` | Injects `EPERM` |
| `@ChaosExecveatE2bigFailAfter` | Injects `E2BIG` starting from the N-th call |
| `@ChaosExecveatEaccesFailAfter` | Injects `EACCES` starting from the N-th call |
| `@ChaosExecveatEmfileFailAfter` | Injects `EMFILE` starting from the N-th call |
| `@ChaosExecveatEnfileFailAfter` | Injects `ENFILE` starting from the N-th call |
| `@ChaosExecveatEnoentFailAfter` | Injects `ENOENT` starting from the N-th call |
| `@ChaosExecveatEnomemFailAfter` | Injects `ENOMEM` starting from the N-th call |
| `@ChaosExecveatEpermFailAfter` | Injects `EPERM` starting from the N-th call |
| `@ChaosExecveatLatency` | Delays every `execveat` call by `delayMs` milliseconds |

---

### `fork`

Intercepts `fork(2)` — the syscall that creates a child process as a copy of the parent.

| Annotation | Effect |
|---|---|
| `@ChaosForkEagain` | Injects `EAGAIN` — process or thread limit reached |
| `@ChaosForkEnomem` | Injects `ENOMEM` — kernel out of memory for fork |
| `@ChaosForkEagainFailAfter` | Injects `EAGAIN` starting from the N-th `fork` call |
| `@ChaosForkEnomemFailAfter` | Injects `ENOMEM` starting from the N-th call |
| `@ChaosForkLatency` | Delays every `fork` call by `delayMs` milliseconds |

---

### `posix_spawn`

Intercepts `posix_spawn(3)` — the POSIX high-level process spawning function.

| Annotation | Effect |
|---|---|
| `@ChaosPosixSpawnEagain` | Injects `EAGAIN` — process limit or resource limit reached |
| `@ChaosPosixSpawnEinval` | Injects `EINVAL` — invalid spawn attributes |
| `@ChaosPosixSpawnEmfile` | Injects `EMFILE` — file-descriptor limit exceeded |
| `@ChaosPosixSpawnEnfile` | Injects `ENFILE` — system-wide file-descriptor limit exceeded |
| `@ChaosPosixSpawnEnoent` | Injects `ENOENT` — executable not found |
| `@ChaosPosixSpawnEnomem` | Injects `ENOMEM` — kernel out of memory |
| `@ChaosPosixSpawnEagainFailAfter` | Injects `EAGAIN` starting from the N-th call |
| `@ChaosPosixSpawnEinvalFailAfter` | Injects `EINVAL` starting from the N-th call |
| `@ChaosPosixSpawnEmfileFailAfter` | Injects `EMFILE` starting from the N-th call |
| `@ChaosPosixSpawnEnfileFailAfter` | Injects `ENFILE` starting from the N-th call |
| `@ChaosPosixSpawnEnoentFailAfter` | Injects `ENOENT` starting from the N-th call |
| `@ChaosPosixSpawnEnomemFailAfter` | Injects `ENOMEM` starting from the N-th call |
| `@ChaosPosixSpawnLatency` | Delays every `posix_spawn` call by `delayMs` milliseconds |

---

### `posix_spawnp`

Intercepts `posix_spawnp(3)` — the PATH-searching variant of `posix_spawn`. Same variants:

| Annotation | Effect |
|---|---|
| `@ChaosPosixSpawnpEagain` | Injects `EAGAIN` |
| `@ChaosPosixSpawnpEinval` | Injects `EINVAL` |
| `@ChaosPosixSpawnpEmfile` | Injects `EMFILE` |
| `@ChaosPosixSpawnpEnfile` | Injects `ENFILE` |
| `@ChaosPosixSpawnpEnoent` | Injects `ENOENT` |
| `@ChaosPosixSpawnpEnomem` | Injects `ENOMEM` |
| `@ChaosPosixSpawnpEagainFailAfter` | Injects `EAGAIN` starting from the N-th call |
| `@ChaosPosixSpawnpEinvalFailAfter` | Injects `EINVAL` starting from the N-th call |
| `@ChaosPosixSpawnpEmfileFailAfter` | Injects `EMFILE` starting from the N-th call |
| `@ChaosPosixSpawnpEnfileFailAfter` | Injects `ENFILE` starting from the N-th call |
| `@ChaosPosixSpawnpEnoentFailAfter` | Injects `ENOENT` starting from the N-th call |
| `@ChaosPosixSpawnpEnomemFailAfter` | Injects `ENOMEM` starting from the N-th call |
| `@ChaosPosixSpawnpLatency` | Delays every `posix_spawnp` call by `delayMs` milliseconds |

---

### `pthread_create`

Intercepts `pthread_create(3)` — the POSIX thread creation function.

| Annotation | Effect |
|---|---|
| `@ChaosPthreadCreateEagain` | Injects `EAGAIN` — thread limit or resource limit reached |
| `@ChaosPthreadCreateEbusy` | Injects `EBUSY` — system resource temporarily unavailable |
| `@ChaosPthreadCreateEinval` | Injects `EINVAL` — invalid thread attributes |
| `@ChaosPthreadCreateEperm` | Injects `EPERM` — insufficient privilege for scheduling policy |
| `@ChaosPthreadCreateEagainFailAfter` | Injects `EAGAIN` starting from the N-th call |
| `@ChaosPthreadCreateEbusyFailAfter` | Injects `EBUSY` starting from the N-th call |
| `@ChaosPthreadCreateEinvalFailAfter` | Injects `EINVAL` starting from the N-th call |
| `@ChaosPthreadCreateEpermFailAfter` | Injects `EPERM` starting from the N-th call |
| `@ChaosPthreadCreateLatency` | Delays every `pthread_create` call by `delayMs` milliseconds |

---

### `waitpid`

Intercepts `waitpid(2)` — the syscall that waits for a child process to change state.

| Annotation | Effect |
|---|---|
| `@ChaosWaitpidEchild` | Injects `ECHILD` — no child process matching the pid |
| `@ChaosWaitpidEintr` | Injects `EINTR` — signal interrupted the wait |
| `@ChaosWaitpidEinval` | Injects `EINVAL` — invalid options argument |
| `@ChaosWaitpidEsrch` | Injects `ESRCH` — no process in the process group (stale pid) |
| `@ChaosWaitpidEchildFailAfter` | Injects `ECHILD` starting from the N-th call |
| `@ChaosWaitpidEintrFailAfter` | Injects `EINTR` starting from the N-th call |
| `@ChaosWaitpidEinvalFailAfter` | Injects `EINVAL` starting from the N-th call |
| `@ChaosWaitpidEsrchFailAfter` | Injects `ESRCH` starting from the N-th call |
| `@ChaosWaitpidLatency` | Delays every `waitpid` call by `delayMs` milliseconds |

---

### Process wildcard (all intercepted process syscalls)

The wildcard selector fires on every intercepted process-management syscall (`fork`, `execve`,
`execveat`, `posix_spawn`, `posix_spawnp`, `pthread_create`, `waitpid`).

| Annotation | Effect |
|---|---|
| `@ChaosWildcardE2big` | Injects `E2BIG` into every intercepted process syscall |
| `@ChaosWildcardEacces` | Injects `EACCES` into every intercepted process syscall |
| `@ChaosWildcardEagain` | Injects `EAGAIN` into every intercepted process syscall |
| `@ChaosWildcardEbusy` | Injects `EBUSY` into every intercepted process syscall |
| `@ChaosWildcardEchild` | Injects `ECHILD` into every intercepted process syscall |
| `@ChaosWildcardEintr` | Injects `EINTR` into every intercepted process syscall |
| `@ChaosWildcardEinval` | Injects `EINVAL` into every intercepted process syscall |
| `@ChaosWildcardEmfile` | Injects `EMFILE` into every intercepted process syscall |
| `@ChaosWildcardEnfile` | Injects `ENFILE` into every intercepted process syscall |
| `@ChaosWildcardEnoent` | Injects `ENOENT` into every intercepted process syscall |
| `@ChaosWildcardEnomem` | Injects `ENOMEM` into every intercepted process syscall |
| `@ChaosWildcardEnosys` | Injects `ENOSYS` into every intercepted process syscall |
| `@ChaosWildcardEperm` | Injects `EPERM` into every intercepted process syscall |
| `@ChaosWildcardEsrch` | Injects `ESRCH` into every intercepted process syscall |
| `@ChaosWildcardE2bigFailAfter` | Injects `E2BIG` starting from the N-th call on any selector |
| `@ChaosWildcardEaccesFailAfter` | Injects `EACCES` starting from the N-th call |
| `@ChaosWildcardEagainFailAfter` | Injects `EAGAIN` starting from the N-th call |
| `@ChaosWildcardEbusyFailAfter` | Injects `EBUSY` starting from the N-th call |
| `@ChaosWildcardEchildFailAfter` | Injects `ECHILD` starting from the N-th call |
| `@ChaosWildcardEintrFailAfter` | Injects `EINTR` starting from the N-th call |
| `@ChaosWildcardEinvalFailAfter` | Injects `EINVAL` starting from the N-th call |
| `@ChaosWildcardEmfileFailAfter` | Injects `EMFILE` starting from the N-th call |
| `@ChaosWildcardEnfileFailAfter` | Injects `ENFILE` starting from the N-th call |
| `@ChaosWildcardEnoentFailAfter` | Injects `ENOENT` starting from the N-th call |
| `@ChaosWildcardEnomemFailAfter` | Injects `ENOMEM` starting from the N-th call |
| `@ChaosWildcardEnosysFailAfter` | Injects `ENOSYS` starting from the N-th call |
| `@ChaosWildcardEpermFailAfter` | Injects `EPERM` starting from the N-th call |
| `@ChaosWildcardEsrchFailAfter` | Injects `ESRCH` starting from the N-th call |
| `@ChaosWildcardLatency` | Delays every intercepted process syscall by `delayMs` milliseconds |

Errno variants: `probability` (`double`, default `1.0`), `id`, `onMissingEnv`.  
FailAfter variants: `failAfterCount` (`int`, default `1`), `id`, `onMissingEnv`.  
Latency variant: `delayMs` (`long`, default `100`), `id`, `onMissingEnv`.

---

## JVM Agent Stressors (`macstab-chaos-java`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-java:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-java</artifactId>`

> These are the most powerful L1 primitives in the framework — they operate inside the JVM
> using the `chaos-testing-java-agent`, enabling failure modes invisible to OS-level tools.
> Requires `@JvmAgentChaos` on the test class instead of `@SyscallLevelChaos`; the agent is
> copied into the container image overlay and injected via `JAVA_TOOL_OPTIONS=-javaagent:...`
> before the JVM starts.
>
> Two families exist: **stressors** (spawn a self-driving background routine for the duration of
> the rule) and **interceptors** (intercept specific JVM or JDK API call sites).

All JVM L1 annotations share:
- **`id`** — container filter (empty = all containers)
- **`onMissingEnv`** — policy when the JVM agent is not active

**Common delay interceptor attributes:** `delayMs` (`long`, default `100`), `maxDelayMs` (`long`, default `100` — set higher for a random range), `id`, `onMissingEnv`.  
**Common gate interceptor attributes:** `maxBlockMs` (`long`, default `30000`), `id`, `onMissingEnv`.  
**Common reject interceptor attributes:** `message` (`String`, default `"rejected by chaos L1"`), `id`, `onMissingEnv`.  
**Common suppress interceptor attributes:** `id`, `onMissingEnv` only.  
**Common inject-exception interceptor attributes:** `exceptionClassName` (`String`, default `"java.io.IOException"`), `message` (`String`, default `"injected by chaos L1"`), `id`, `onMissingEnv`.  
**Common clock-skew interceptor attributes:** `skewMs` (`long`, default `-60000`), `mode` (`ChaosEffect.ClockSkewMode`, default `FIXED`), `id`, `onMissingEnv`.

---

### JVM Stressors

#### `@ChaosSafepointStorm`
Drives a continuous storm of JVM safepoints by issuing `System.gc()` calls on a tight interval,
repeatedly stopping all application threads for stop-the-world pauses. Exercises latency SLOs,
deadline propagation, and liveness-probe resilience.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `gcIntervalMs` | `long` | `100` | Interval between forced GC calls in milliseconds |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when the JVM agent is not active |

---

#### `@ChaosCodeCachePressure`
Forces the JVM's JIT compiler to fill the code cache by generating and hot-compiling a large number
of synthetic methods, driving the JVM into code-cache saturation mode where new compilations are
queued indefinitely and hot application methods may run in the interpreter.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `classCount` | `int` | `5000` | Number of synthetic classes to JIT-compile |
| `methodsPerClass` | `int` | `50` | Methods per generated class |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when the JVM agent is not active |

---

#### `@ChaosVirtualThreadCarrierPinning`
Defeats Project Loom's virtual-thread multiplexing by holding carrier threads inside `synchronized`
blocks for a configurable duration, simulating the carrier-pinning failure mode. Most impactful on
Java 21–23 (before JEP 491). Exercises virtual-thread throughput degradation and
`jdk.VirtualThreadPinned` JFR event monitoring.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `pinnedThreadCount` | `int` | `4` | Number of carrier threads to pin |
| `pinDurationMs` | `long` | `100` | Per-cycle pin duration in milliseconds |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when the JVM agent is not active |

---

#### `@ChaosMetaspacePressure`
Exhausts the JVM's Metaspace by generating and loading large numbers of synthetic classes via
isolated class loaders that can never be unloaded, simulating class-loading leaks in
proxy-generating frameworks (Spring, Hibernate).

| Attribute | Type | Default | Description |
|---|---|---|---|
| `generatedClassCount` | `int` | `10000` | Number of synthetic classes to generate |
| `fieldsPerClass` | `int` | `10` | Static fields per generated class (increases per-class Metaspace footprint) |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when the JVM agent is not active |

---

#### `@ChaosHeapPressure`
Retains a configurable amount of Java heap memory in fixed-size chunks for the duration of the
test, shrinking the effective heap available to the application and driving up GC frequency without
generating temporary garbage (long-lived retention, old-gen pressure).

| Attribute | Type | Default | Description |
|---|---|---|---|
| `bytes` | `long` | `67108864` (64 MB) | Total bytes to allocate and retain |
| `chunkSizeBytes` | `int` | `1048576` (1 MB) | Per-chunk size in bytes |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when the JVM agent is not active |

---

#### `@ChaosGcPressure`
Sustains a configurable allocation rate inside the target container's JVM to drive continuous GC
activity, simulating the garbage production of a high-throughput service under peak load
(young-gen / short-lived-allocation pressure).

| Attribute | Type | Default | Description |
|---|---|---|---|
| `allocationRateBytesPerSecond` | `long` | `104857600` (100 MB/s) | Allocation rate to sustain |
| `durationMs` | `long` | `60000` | How long the stressor runs in milliseconds |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when the JVM agent is not active |

---

#### `@ChaosDirectBufferPressure`
Exhausts the JVM's off-heap direct buffer memory by allocating and retaining `ByteBuffer.allocateDirect()`
buffers, simulating NIO or Netty direct-memory exhaustion. Stressor defeats the JVM's emergency-GC
recovery by holding all buffers strongly.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `totalBytes` | `long` | `268435456` (256 MB) | Total off-heap bytes to allocate and retain |
| `bufferSizeBytes` | `int` | `1048576` (1 MB) | Per-buffer size |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when the JVM agent is not active |

---

#### `@ChaosMonitorContention`
Generates sustained monitor contention by running N threads that compete for a single shared lock
with configurable hold times, simulating the lock-contention profile of a high-concurrency
production workload with a bottleneck resource.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `contendingThreadCount` | `int` | `8` | Number of contending threads (>= 2) |
| `lockHoldMs` | `long` | `50` | Per-thread lock-hold duration in milliseconds |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when the JVM agent is not active |

---

#### `@ChaosDeadlock`
Injects a permanent monitor deadlock by locking N synthetic threads in a circular lock-acquisition
cycle for the duration of the test, allowing validation of `ThreadMXBean.findDeadlockedThreads()`
monitoring and alerting.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `participantCount` | `int` | `2` | Number of threads to deadlock (>= 2) |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when the JVM agent is not active |

---

#### `@ChaosThreadLeak`
Injects a thread leak by spawning a configurable number of platform threads that loop indefinitely,
consuming OS thread handles, kernel thread stacks, and JVM thread-table entries. Exercises
`ThreadCount` metric alerting and `OutOfMemoryError: Unable to create new native thread` handling.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `threadCount` | `int` | `50` | Number of threads to leak |
| `namePrefix` | `String` | `"chaos-l1-leaked-"` | Name prefix for leaked threads (for identification in thread dumps) |
| `daemon` | `boolean` | `true` | Whether leaked threads are daemon threads; `false` blocks JVM exit |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when the JVM agent is not active |

---

#### `@ChaosThreadLocalLeak`
Simulates a `ThreadLocal` memory leak by planting large, never-removed `ThreadLocal` values into
pool threads, reproducing the pattern where a framework stores per-request data in a pooled thread
and forgets to clean up after the request completes.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `entriesPerThread` | `int` | `100` | Number of `ThreadLocal` entries to plant per pool thread |
| `valueSizeBytes` | `int` | `65536` (64 KB) | Byte-array value size per entry |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when the JVM agent is not active |

---

#### `@ChaosFinalizerBacklog`
Creates a large backlog of objects with slow `finalize()` methods faster than the JVM's single
finalizer thread can drain them, simulating GC starvation caused by finalizable-object accumulation
in legacy libraries that use `finalize()` for resource cleanup.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `objectCount` | `int` | `1000` | Number of objects with slow finalizers to create |
| `finalizerDelayMs` | `long` | `100` | Per-finalizer sleep duration in milliseconds |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when the JVM agent is not active |

---

#### `@ChaosReferenceQueueFlood`
Floods the JVM's internal reference-processing queue with a burst of `WeakReference` objects on a
tight cycle, overwhelming the `ReferenceHandler` thread and stalling GC reference processing,
`Cleaner` actions, and `DirectByteBuffer` reclamation.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `referenceCount` | `int` | `10000` | `WeakReference` objects created per flood cycle |
| `floodIntervalMs` | `long` | `100` | Interval between flood cycles in milliseconds |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when the JVM agent is not active |

---

#### `@ChaosStringInternPressure`
Exhausts the JVM's string intern table by interning a large number of unique synthetic strings that
are never released, simulating legacy XML parsers or ORM frameworks that incorrectly intern
arbitrary user-supplied strings.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `internCount` | `int` | `100000` | Number of strings to intern |
| `stringLengthBytes` | `int` | `64` | Per-string length in bytes |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when the JVM agent is not active |

---

#### `@ChaosKeepAlive`
Spawns a non-terminating background thread that ignores interrupts, simulating a misbehaving
background task that refuses to stop on SIGTERM. When `daemon = false`, blocks the JVM from
completing graceful shutdown, exercising Kubernetes `terminationGracePeriodSeconds` and
shutdown-hook ordering.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `threadName` | `String` | `"chaos-l1-keepalive"` | Name of the kept-alive thread |
| `daemon` | `boolean` | `true` | Whether the thread is a daemon; `false` blocks JVM exit |
| `heartbeatMs` | `long` | `1000` | Interval between park cycles in milliseconds |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when the JVM agent is not active |

---

### JVM Interceptors — `CompletableFuture` (async)

#### `@ChaosAsyncCancelDelay`
Parks the calling thread for `delayMs`–`maxDelayMs` milliseconds inside every
`CompletableFuture.cancel(mayInterrupt)` call, stretching cancellation latency without suppressing it.

#### `@ChaosAsyncCancelSuppress`
Makes `CompletableFuture.cancel(mayInterrupt)` silently return `false` without transitioning the
future to the cancelled state — tasks the caller believes cancelled continue running.

#### `@ChaosAsyncCompleteExceptionalCompletion`
Intercepts `CompletableFuture.complete(value)` and instead completes the future exceptionally,
causing all downstream stages and `get()` callers to receive a configurable exception.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `message` | `String` | `"completed exceptionally by chaos L1"` | Exception message |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when the JVM agent is not active |

#### `@ChaosAsyncCompleteExceptionallySuppress`
Makes `CompletableFuture.completeExceptionally(ex)` silently return `false` without completing the
future, leaving it permanently in the pending state.

#### `@ChaosAsyncCompleteSuppress`
Suppresses `CompletableFuture.complete(value)` so the future is never completed normally, causing
all `get()` calls to block indefinitely.

---

### JVM Interceptors — Class loading

#### `@ChaosClassLoadDelay`
Delays every `ClassLoader.loadClass(name)` call by `delayMs`–`maxDelayMs` milliseconds before
delegating, simulating a slow or overloaded classloader (e.g., a remote class server or network
filesystem-backed classpath).

#### `@ChaosClassLoadInjectException`
Throws a configurable exception inside `ClassLoader.loadClass(name)`, causing all class loading to
fail. Exercises `ClassNotFoundException` recovery paths and lazy-initialisation retry logic.

#### `@ChaosClassDefineDelay`
Delays every `ClassLoader.defineClass(...)` call by `delayMs`–`maxDelayMs` milliseconds,
simulating a slow bytecode verification or instrumentation pass during class definition.

#### `@ChaosClassDefineSuppress`
Suppresses `ClassLoader.defineClass(...)`, discarding the bytecode without defining the class and
causing the caller to receive a null class reference.

#### `@ChaosResourceLoadDelay`
Delays every `ClassLoader.getResourceAsStream(name)` call by `delayMs`–`maxDelayMs` milliseconds,
simulating a slow classpath resource lookup (e.g., configuration files or templates on a network
filesystem).

#### `@ChaosResourceLoadSuppress`
Makes `ClassLoader.getResourceAsStream(name)` return `null`, simulating a missing classpath
resource and exercising resource-not-found fallback paths.

---

### JVM Interceptors — DNS (JVM-level)

#### `@ChaosDnsResolveDelay`
Delays every `InetAddress.getAllByName(host)` / `getByName(host)` call by `delayMs`–`maxDelayMs`
milliseconds, simulating a slow DNS resolver without failing the lookup.

#### `@ChaosDnsResolveInjectException`
Throws a configurable exception inside `InetAddress.getAllByName(host)`, causing all DNS resolution
in the JVM to fail (distinct from the OS-level `@ChaosForwardEai*` annotations which act via
`LD_PRELOAD`).

---

### JVM Interceptors — Executor service

#### `@ChaosExecutorSubmitDelay`
Parks the submitting thread for `delayMs`–`maxDelayMs` milliseconds inside every
`ExecutorService.submit(...)` and `ForkJoinPool.submit(...)` call, stretching task hand-off latency.

#### `@ChaosExecutorSubmitGate`
Blocks every `ExecutorService.submit(...)` call on an internal latch until the test releases the
gate or `maxBlockMs` elapses, enabling deterministic queue-depth assertions.

#### `@ChaosExecutorSubmitReject`
Makes every `ExecutorService.submit(...)` and `ForkJoinPool.submit(...)` throw
`RejectedExecutionException` before the task enters the queue.

#### `@ChaosExecutorWorkerRunDelay`
Parks the worker thread for `delayMs`–`maxDelayMs` milliseconds at task execution time (inside the
worker's `run()` loop), stretching per-task execution start latency.

#### `@ChaosExecutorWorkerRunReject`
Throws `RejectedExecutionException` from the worker thread's `run()` loop when a task is about to
execute, simulating a saturated thread pool that abandons tasks before running them.

#### `@ChaosExecutorShutdownDelay`
Delays every `ExecutorService.shutdown()` and `shutdownNow()` call by `delayMs`–`maxDelayMs`
milliseconds, simulating a slow pool shutdown path.

#### `@ChaosExecutorShutdownReject`
Makes every `ExecutorService.shutdown()` and `shutdownNow()` throw an exception, simulating a pool
implementation that refuses shutdown.

#### `@ChaosExecutorAwaitTerminationDelay`
Delays every `ExecutorService.awaitTermination(timeout, unit)` call by `delayMs`–`maxDelayMs`
milliseconds before delegating, stretching the total shutdown wait.

#### `@ChaosExecutorAwaitTerminationGate`
Blocks every `ExecutorService.awaitTermination(...)` call on an internal latch until the test
releases the gate or `maxBlockMs` elapses.

#### `@ChaosForkJoinTaskRunDelay`
Parks the worker thread for `delayMs`–`maxDelayMs` milliseconds inside `ForkJoinTask.exec()` (the
execution entry point for tasks submitted to `ForkJoinPool`), delaying parallel-stream and
`CompletableFuture` computations.

#### `@ChaosForkJoinTaskRunReject`
Throws `RejectedExecutionException` from `ForkJoinTask.exec()`, causing all `ForkJoinPool`
task executions (including parallel streams) to fail.

---

### JVM Interceptors — File I/O (Java streams)

#### `@ChaosFileIoReadDelay`
Delays every `FileInputStream.read(...)` and `FileReader.read(...)` call by `delayMs`–`maxDelayMs`
milliseconds, simulating a slow storage device at the Java stream layer.

#### `@ChaosFileIoReadInjectException`
Throws a configurable exception inside `FileInputStream.read(...)`, simulating an I/O error on the
Java read path.

#### `@ChaosFileIoWriteDelay`
Delays every `FileOutputStream.write(...)` and `FileWriter.write(...)` call by
`delayMs`–`maxDelayMs` milliseconds, simulating a slow or backlogged write path.

#### `@ChaosFileIoWriteInjectException`
Throws a configurable exception inside `FileOutputStream.write(...)`, simulating a storage error
on the Java write path.

---

### JVM Interceptors — HTTP client (java.net.http)

#### `@ChaosHttpClientSendDelay`
Delays every `HttpClient.send(request, bodyHandler)` call by `delayMs`–`maxDelayMs` milliseconds
before delegating, simulating a slow network or remote endpoint.

#### `@ChaosHttpClientSendGate`
Blocks every `HttpClient.send(...)` call until the test releases the gate or `maxBlockMs` elapses.

#### `@ChaosHttpClientSendReject`
Makes every `HttpClient.send(...)` throw an `IOException` with `message`, simulating a completely
unreachable endpoint.

#### `@ChaosHttpClientSendInjectException`
Throws a configurable exception class from `HttpClient.send(...)`.

#### `@ChaosHttpClientSendAsyncDelay`
Delays every `HttpClient.sendAsync(request, bodyHandler)` call by `delayMs`–`maxDelayMs`
milliseconds before returning the `CompletableFuture`.

#### `@ChaosHttpClientSendAsyncReject`
Makes every `HttpClient.sendAsync(...)` throw a `RuntimeException`, causing the returned future
to be never created.

#### `@ChaosHttpClientSendAsyncInjectException`
Completes the `CompletableFuture` returned by `sendAsync(...)` exceptionally with a configurable
exception class and message.

---

### JVM Interceptors — JDBC

#### `@ChaosJdbcConnectionAcquireDelay`
Delays every `DataSource.getConnection()` call by `delayMs`–`maxDelayMs` milliseconds, simulating
a slow or backlogged connection pool.

#### `@ChaosJdbcConnectionAcquireInjectException`
Throws a configurable exception from `DataSource.getConnection()`, simulating connection pool
exhaustion or a database that refuses new connections.

#### `@ChaosJdbcPreparedStatementDelay`
Delays every `Connection.prepareStatement(sql)` call by `delayMs`–`maxDelayMs` milliseconds,
simulating a slow statement-preparation path (query plan cache miss, schema lock contention).

#### `@ChaosJdbcPreparedStatementInjectException`
Throws a configurable exception from `Connection.prepareStatement(sql)`, simulating a SQL syntax
error or a broken connection detected at prepare time.

#### `@ChaosJdbcStatementExecuteDelay`
Delays every `Statement.execute(...)`, `executeQuery(...)`, and `executeUpdate(...)` call by
`delayMs`–`maxDelayMs` milliseconds, simulating a slow query or an overloaded database.

#### `@ChaosJdbcStatementExecuteInjectException`
Throws a configurable exception from `Statement.execute(...)`, simulating a deadlock, constraint
violation, or transient database error during execution.

#### `@ChaosJdbcTransactionCommitDelay`
Delays every `Connection.commit()` call by `delayMs`–`maxDelayMs` milliseconds, simulating a slow
commit path under write-heavy load or a slow WAL flush.

#### `@ChaosJdbcTransactionCommitInjectException`
Throws a configurable exception from `Connection.commit()`, simulating a commit failure (serialisation
conflict, network error, or storage failure).

#### `@ChaosJdbcTransactionRollbackDelay`
Delays every `Connection.rollback()` call by `delayMs`–`maxDelayMs` milliseconds, simulating a
slow rollback path under lock contention.

#### `@ChaosJdbcTransactionRollbackInjectException`
Throws a configurable exception from `Connection.rollback()`, simulating a rollback failure.

---

### JVM Interceptors — JVM runtime

#### `@ChaosSystemClockMillisSkew`
Adds `skewMs` milliseconds to every `System.currentTimeMillis()` return value, simulating a
host clock that is ahead of or behind true wall time.

#### `@ChaosSystemClockNanosSkew`
Adds the equivalent of `skewMs` milliseconds to every `System.nanoTime()` return value,
simulating monotonic clock drift (affects timeouts and elapsed-time measurements).

#### `@ChaosInstantNowSkew`
Shifts every `Instant.now()` return by `skewMs` milliseconds, targeting the `java.time` API.

#### `@ChaosLocalDateTimeNowSkew`
Shifts every `LocalDateTime.now()` return by `skewMs` milliseconds.

#### `@ChaosZonedDateTimeNowSkew`
Shifts every `ZonedDateTime.now()` return by `skewMs` milliseconds.

#### `@ChaosDateNewSkew`
Shifts every `new java.util.Date()` (no-arg constructor) by `skewMs` milliseconds; explicit-epoch
constructors (`new Date(millis)`) are not intercepted.

All six clock-skew annotations share: `skewMs` (`long`, default `-60000`), `mode` (`ChaosEffect.ClockSkewMode`, default `FIXED`; options: `FIXED`, `DRIFT`, `FREEZE`), `id`, `onMissingEnv`.

#### `@ChaosDirectBufferAllocateDelay`
Delays every `ByteBuffer.allocateDirect(capacity)` call by `delayMs`–`maxDelayMs` milliseconds,
simulating a slow off-heap allocator.

#### `@ChaosDirectBufferAllocateSuppress`
Makes every `ByteBuffer.allocateDirect(capacity)` return `null`, simulating off-heap memory
exhaustion at the Java API layer.

#### `@ChaosSystemGcRequestDelay`
Delays every `System.gc()` call by `delayMs`–`maxDelayMs` milliseconds before delegating.

#### `@ChaosSystemGcRequestSuppress`
Discards every `System.gc()` call without issuing it to the JVM, simulating a `DisableExplicitGC`
JVM flag effect.

#### `@ChaosSystemExitRequestDelay`
Delays every `System.exit(status)` call by `delayMs`–`maxDelayMs` milliseconds before delegating,
stretching the window between shutdown trigger and JVM exit.

#### `@ChaosSystemExitRequestSuppress`
Discards every `System.exit(status)` call, preventing the JVM from exiting normally and exercising
code that assumes `System.exit` always terminates the process.

#### `@ChaosJmxGetAttrDelay`
Delays every `MBeanServerConnection.getAttribute(name, attribute)` call by `delayMs`–`maxDelayMs`
milliseconds, simulating a slow JMX attribute read.

#### `@ChaosJmxGetAttrInjectException`
Throws a configurable exception from `MBeanServerConnection.getAttribute(...)`.

#### `@ChaosJmxInvokeDelay`
Delays every `MBeanServerConnection.invoke(name, operationName, ...)` call by `delayMs`–`maxDelayMs`
milliseconds, simulating a slow JMX operation invocation.

#### `@ChaosJmxInvokeInjectException`
Throws a configurable exception from `MBeanServerConnection.invoke(...)`.

#### `@ChaosJndiLookupDelay`
Delays every `InitialContext.lookup(name)` call by `delayMs`–`maxDelayMs` milliseconds, simulating
a slow JNDI directory service.

#### `@ChaosJndiLookupInjectException`
Throws a configurable exception from `InitialContext.lookup(name)`, simulating a missing JNDI
binding or a broken naming service.

#### `@ChaosObjectSerializeDelay`
Delays every `ObjectOutputStream.writeObject(obj)` call by `delayMs`–`maxDelayMs` milliseconds,
simulating a slow Java serialisation path.

#### `@ChaosObjectSerializeInjectException`
Throws a configurable exception from `ObjectOutputStream.writeObject(obj)`.

#### `@ChaosObjectDeserializeDelay`
Delays every `ObjectInputStream.readObject()` call by `delayMs`–`maxDelayMs` milliseconds,
simulating slow Java deserialisation.

#### `@ChaosObjectDeserializeInjectException`
Throws a configurable exception from `ObjectInputStream.readObject()`.

#### `@ChaosReflectionInvokeDelay`
Delays every `Method.invoke(target, args...)` call by `delayMs`–`maxDelayMs` milliseconds,
simulating a slow reflective dispatch path.

#### `@ChaosNativeLibraryLoadDelay`
Delays every `System.loadLibrary(name)` and `System.load(path)` call by `delayMs`–`maxDelayMs`
milliseconds, simulating a slow native library load from a network filesystem.

#### `@ChaosNativeLibraryLoadInjectException`
Throws a configurable exception from `System.loadLibrary(name)` / `System.load(path)`, simulating
a missing or incompatible native library.

#### `@ChaosZipDeflateDelay`
Delays every `Deflater.deflate(...)` call by `delayMs`–`maxDelayMs` milliseconds, simulating a
slow CPU-bound compression path.

#### `@ChaosZipInflateDelay`
Delays every `Inflater.inflate(...)` call by `delayMs`–`maxDelayMs` milliseconds, simulating a
slow decompression path.

---

### JVM Interceptors — Monitor and park

#### `@ChaosMonitorEnterDelay`
Parks the acquiring thread for `delayMs`–`maxDelayMs` milliseconds before every `synchronized`
monitor acquisition, simulating pathological lock-acquisition latency.

#### `@ChaosMonitorEnterGate`
Blocks every `synchronized` monitor acquisition on an internal latch until the test releases the
gate or `maxBlockMs` elapses, enabling deterministic contention scenarios.

#### `@ChaosThreadParkDelay`
Delays every `LockSupport.park(...)` call by `delayMs`–`maxDelayMs` milliseconds before delegating,
stretching the time threads spend entering the parked state.

#### `@ChaosThreadParkGate`
Blocks every `LockSupport.park(...)` call on a gate until the test releases it or `maxBlockMs`
elapses.

#### `@ChaosThreadParkSpuriousWakeup`
Immediately returns from every `LockSupport.park(...)` call without waiting (spurious wakeup),
exercising all wait-loop correctness checks in `AbstractQueuedSynchronizer`-based locks,
`ReentrantLock`, `CountDownLatch`, and similar primitives.

---

### JVM Interceptors — NIO channels and selector

#### `@ChaosNioChannelConnectDelay`
Delays every `SocketChannel.connect(address)` call by `delayMs`–`maxDelayMs` milliseconds,
simulating a slow NIO connect path.

#### `@ChaosNioChannelConnectReject`
Throws an `IOException` with `message` from every `SocketChannel.connect(address)`, simulating
a connection refused at the NIO channel level.

#### `@ChaosNioChannelAcceptDelay`
Delays every `ServerSocketChannel.accept()` call by `delayMs`–`maxDelayMs` milliseconds, simulating
a slow server-side accept loop.

#### `@ChaosNioChannelAcceptSuppress`
Makes `ServerSocketChannel.accept()` return `null` (as permitted by the NIO contract for
non-blocking channels with no pending connections), exercising accept-null handling in NIO servers.

#### `@ChaosNioChannelReadDelay`
Delays every `ReadableByteChannel.read(buffer)` call by `delayMs`–`maxDelayMs` milliseconds,
simulating slow data arrival at the NIO read path.

#### `@ChaosNioChannelReadInjectException`
Throws a configurable exception from `ReadableByteChannel.read(buffer)`.

#### `@ChaosNioChannelWriteDelay`
Delays every `WritableByteChannel.write(buffer)` call by `delayMs`–`maxDelayMs` milliseconds,
simulating a slow NIO write path.

#### `@ChaosNioChannelWriteInjectException`
Throws a configurable exception from `WritableByteChannel.write(buffer)`.

#### `@ChaosNioSelectorSelectDelay`
Delays every `Selector.select(...)` call by `delayMs`–`maxDelayMs` milliseconds before delegating,
simulating a slow or overloaded I/O event loop.

#### `@ChaosNioSelectorSelectSpuriousWakeup`
Immediately returns 0 from every `Selector.select(...)` call without waiting, simulating the
Linux `epoll_wait` spurious-wakeup bug and exercising all NIO event-loop guard conditions.

---

### JVM Interceptors — blocking Socket API

These annotations target `java.net.Socket` / `java.net.ServerSocket` — the blocking I/O path
used by Tomcat BIO connector, embedded ZooKeeper servers, and custom server-socket implementations.
For NIO-based frameworks (Netty, Undertow) use the `@ChaosNioChannel*` annotations above.

**Shared attributes** (all blocking-socket interceptors):

| Attribute | Type | Default | Description |
|---|---|---|---|
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when JVM agent is unavailable |

Delay variants additionally carry:

| Attribute | Type | Default | Description |
|---|---|---|---|
| `delayMs` | `long` | `100` | Minimum delay in milliseconds |
| `maxDelayMs` | `long` | `100` | Maximum delay (equal = deterministic) |

InjectException variants carry:

| Attribute | Type | Default | Description |
|---|---|---|---|
| `exceptionClassName` | `String` | `"java.io.IOException"` | Binary class name of exception to throw |

#### `@ChaosSocketAcceptDelay`
Delays every `ServerSocket.accept()` call by `delayMs`–`maxDelayMs` milliseconds before dequeuing
the next inbound connection. Models GC-pause-induced accept-queue overflow: the kernel backlog fills
while the acceptor thread is held, causing new SYN packets to be dropped.

#### `@ChaosSocketAcceptSuppress`
Causes `ServerSocket.accept()` to throw `SocketTimeoutException` immediately (as if `SO_TIMEOUT`
expired) without dequeuing any connection, exercising accept-failure retry loops in blocking servers.

Attributes: `id`, `onMissingEnv` only (no delay or exception fields).

#### `@ChaosSocketConnectDelay`
Delays every `Socket.connect(address, timeout)` call by `delayMs`–`maxDelayMs` milliseconds,
simulating a slow three-way handshake on an overloaded server.

#### `@ChaosSocketConnectInjectException`
Throws a configurable exception from `Socket.connect(...)`, simulating connection refused or
network unreachable at the blocking socket level.

#### `@ChaosSocketConnectReject`
Injects a `ConnectException` with a custom `message` from `Socket.connect(...)`.

Attributes: `message` (`String`, default `"rejected by chaos L1"`), `id`, `onMissingEnv`.

#### `@ChaosSocketCloseDelay`
Delays every `Socket.close()` / `ServerSocket.close()` call by `delayMs`–`maxDelayMs` milliseconds,
simulating a slow TCP FIN exchange or a half-open connection that blocks the close path.

#### `@ChaosSocketCloseInjectException`
Throws a configurable exception from `Socket.close()`, exercising connection-pool handling of
sockets that fail during close — a silent resource-leak source in many pool implementations.

#### `@ChaosSocketReadDelay`
Delays every `InputStream.read(...)` call obtained from a `Socket` by `delayMs`–`maxDelayMs`
milliseconds, simulating a slow remote peer or a half-open connection where data stops arriving.

#### `@ChaosSocketReadInjectException`
Throws a configurable exception from `InputStream.read(...)` on a socket stream, exercising
mid-stream failure handling in HTTP/1.1 keep-alive, JDBC result streaming, and similar protocols.

#### `@ChaosSocketWriteDelay`
Delays every `OutputStream.write(...)` call on a socket stream by `delayMs`–`maxDelayMs`
milliseconds, simulating kernel send-buffer saturation or a slow consumer on the remote end.

#### `@ChaosSocketWriteInjectException`
Throws a configurable exception from `OutputStream.write(...)` on a socket stream, exercising
broken-pipe and partial-write handling in protocols that stream data over a persistent connection.

---

### JVM Interceptors — Queue (`BlockingQueue`)

#### `@ChaosQueueOfferDelay`
Delays every `BlockingQueue.offer(element)` call by `delayMs`–`maxDelayMs` milliseconds.

#### `@ChaosQueueOfferSuppress`
Makes every `BlockingQueue.offer(element)` return `false` without enqueuing, simulating a
permanently full queue.

#### `@ChaosQueuePollDelay`
Delays every `BlockingQueue.poll(...)` call by `delayMs`–`maxDelayMs` milliseconds.

#### `@ChaosQueuePollSuppress`
Makes every `BlockingQueue.poll(...)` return `null` without removing an element, simulating
an empty queue (even when elements are present).

#### `@ChaosQueuePutDelay`
Delays every `BlockingQueue.put(element)` call by `delayMs`–`maxDelayMs` milliseconds before
the blocking put proceeds.

#### `@ChaosQueuePutGate`
Blocks every `BlockingQueue.put(element)` call on an internal latch until the test releases
the gate or `maxBlockMs` elapses.

#### `@ChaosQueueTakeDelay`
Delays every `BlockingQueue.take()` call by `delayMs`–`maxDelayMs` milliseconds before the
blocking take proceeds.

#### `@ChaosQueueTakeGate`
Blocks every `BlockingQueue.take()` call on a gate until the test releases it or `maxBlockMs`
elapses.

---

### JVM Interceptors — Scheduled executor

#### `@ChaosScheduleSubmitDelay`
Delays every `ScheduledExecutorService.schedule(...)`, `scheduleAtFixedRate(...)`, and
`scheduleWithFixedDelay(...)` call by `delayMs`–`maxDelayMs` milliseconds before the task is
registered with the scheduler.

#### `@ChaosScheduleSubmitSuppress`
Discards every `ScheduledExecutorService.schedule*` call, preventing the task from ever being
registered, simulating a scheduler that silently drops tasks.

#### `@ChaosScheduleTickDelay`
Delays every scheduled-task execution by `delayMs`–`maxDelayMs` milliseconds at the tick-fire
point (when the scheduler timer fires to execute the task), simulating a slow scheduler tick.

#### `@ChaosScheduleTickSuppress`
Suppresses every scheduled-task execution at tick time (the timer fires but the task is discarded),
simulating a scheduler that fires but fails to dispatch work.

---

### JVM Interceptors — Shutdown hooks

#### `@ChaosShutdownHookRegisterDelay`
Delays every `Runtime.getRuntime().addShutdownHook(thread)` call by `delayMs`–`maxDelayMs`
milliseconds, simulating a slow shutdown-hook registration path during startup.

#### `@ChaosShutdownHookRegisterReject`
Makes every `Runtime.addShutdownHook(thread)` throw an exception with `message`, simulating a
JVM that refuses new shutdown hooks (e.g., already shutting down).

---

### JVM Interceptors — SSL/TLS

#### `@ChaosSslHandshakeDelay`
Delays every `SSLEngine.beginHandshake()` / `SSLSocket` handshake call by `delayMs`–`maxDelayMs`
milliseconds, simulating a slow TLS negotiation (overloaded CA OCSP responder, slow certificate
validation, or heavy cipher suite).

#### `@ChaosSslHandshakeInjectException`
Throws a configurable exception from `SSLEngine.beginHandshake()`, simulating a TLS handshake
failure (expired certificate, CA mismatch, cipher negotiation failure).

---

### JVM Interceptors — Thread lifecycle

#### `@ChaosThreadStartDelay`
Delays every `Thread.start()` call on platform threads by `delayMs`–`maxDelayMs` milliseconds
before the OS thread is created, simulating thread-creation latency under resource pressure.

#### `@ChaosThreadStartReject`
Makes every `Thread.start()` on platform threads throw an exception with `message`, simulating
the `OutOfMemoryError: Unable to create new native thread` failure mode.

#### `@ChaosVirtualThreadStartDelay`
Delays every virtual-thread `Thread.start()` call (Java 21+) by `delayMs`–`maxDelayMs`
milliseconds, simulating scheduler saturation for virtual-thread-based workloads.

#### `@ChaosVirtualThreadStartReject`
Makes every virtual-thread `Thread.start()` throw an exception with `message`, simulating virtual
thread exhaustion or a disabled `VirtualThread` scheduler.

#### `@ChaosThreadSleepDelay`
Delays every `Thread.sleep(millis)` call by `delayMs`–`maxDelayMs` milliseconds before the actual
sleep begins, inflating all sleep-based timing in the application.

#### `@ChaosThreadSleepSuppress`
Makes every `Thread.sleep(millis)` return immediately (zero elapsed time), exercising code that
relies on sleep-based back-off or rate limiting.

---

### JVM Interceptors — `ThreadLocal`

#### `@ChaosThreadLocalGetDelay`
Delays every `ThreadLocal.get()` call by `delayMs`–`maxDelayMs` milliseconds, simulating a slow
thread-local lookup (abnormal scenario for benchmarking or contention modelling).

#### `@ChaosThreadLocalGetSuppress`
Makes every `ThreadLocal.get()` return `null` regardless of what was set, simulating a cleared or
missing thread-local value and exercising null-safety in thread-local consumer code.

#### `@ChaosThreadLocalSetSuppress`
Discards every `ThreadLocal.set(value)` call without storing the value, simulating a thread-local
implementation that silently drops writes and causing subsequent `get()` calls to return the
initial value.

---

### JVM Method Interceptors (generic)

#### `@ChaosMethodEnterInjectException`
Throws a configurable exception at every matched method entry point, providing a general-purpose
escape hatch for injecting failures into arbitrary application code without modifying sources.
Pattern matching uses prefix semantics against binary class names and method names.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `classPattern` | `String` | `""` | Prefix matched against the binary class name (e.g. `"com.example.service"`) |
| `methodNamePattern` | `String` | `""` | Prefix matched against the method name (e.g. `"save"`) |
| `exceptionClassName` | `String` | `"java.io.IOException"` | Binary name of the exception to throw |
| `message` | `String` | `"injected at METHOD_ENTER by chaos L1"` | Exception detail message |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when the JVM agent is not active |

---

#### `@ChaosMethodExitCorruptReturn`
Replaces the return value of every matched method with a synthetic corrupt value at method exit,
simulating silent data corruption or a misbehaving dependency that returns a legal but wrong result.
The method body executes fully (all side effects occur) before the return value is substituted.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `classPattern` | `String` | `""` | Prefix matched against the binary class name |
| `methodNamePattern` | `String` | `""` | Prefix matched against the method name |
| `strategy` | `ChaosEffect.ReturnValueStrategy` | `NULL` | Substitution strategy: `NULL`, `ZERO`, `EMPTY`, `BOUNDARY_MAX`, or `BOUNDARY_MIN` |
| `id` | `String` | `""` | Container filter |
| `onMissingEnv` | `OnMissingEnv` | `ERROR` | Policy when the JVM agent is not active |
