# PHASE 3 IMPLEMENTATION PLAN - HYBRID ARCHITECTURE

**Status:** Ready to implement  
**Architecture:** Per-annotation INSTANCE + Base interface helpers  
**Estimated Time:** 2-3 hours

---

## ARCHITECTURE SUMMARY

### 1. Per-Annotation INSTANCE (Primary UX)
```java
@RedisStandalone
public @interface RedisStandalone {
    ContainerManager<StandaloneRedis> INSTANCE = ...;  // ✅ Type-safe
}

// Usage (95% of cases)
StandaloneRedis cache = RedisStandalone.INSTANCE.get("cache");
```

### 2. Base Interface Helpers (Unified Access)
```java
sealed interface Redis {
    static List<Redis> getAll() { ... }  // All topologies
    static Redis get(String id) { ... }  // Any topology
}

// Usage (5% of cases)
List<Redis> all = Redis.getAll();
Redis any = Redis.get("cache");
```

### 3. Extension Tracks Both
- By annotation class → for type-safe access
- By base interface → for unified access

---

## IMPLEMENTATION TASKS

### ✅ COMPLETED

**Task 1.1:** ChaosContainers universal API
**Task 1.2:** ContainerManager generic class

### ⏳ TODO

**Task 1.3:** Update ChaosTestingExtension
- Add ThreadLocal for base type tracking
- Add methods: `getAllConnectionInfoByBaseType()`, `getConnectionInfoByBaseType()`
- Store connection info by both annotation + base type

**Task 2:** Create Redis API Package
- `Redis` sealed interface + static helpers
- `StandaloneRedis` record
- `SentinelRedis` record  
- `Endpoint` record
- `RedisTopology` enum

**Task 3:** Update Redis Annotations
- Add `ContainerManager<T> INSTANCE` field
- Reference ChaosContainers in INSTANCE initialization

**Task 4:** Update Redis Plugins
- RedisPlugin returns `StandaloneRedis` record
- Create SentinelPlugin (returns `SentinelRedis` record)

**Task 5:** Integration Tests
- Test per-annotation INSTANCE access
- Test base interface helpers
- Test mixed topology scenarios

---

## NEXT STEPS

**Continue with Task 1.3:** Update ChaosTestingExtension to track base types

**Implementation Strategy:**
1. Add second ThreadLocal for base type registry
2. Detect base interfaces via reflection (record implements X)
3. Store connection info in both registries
4. Add public static methods for base type access

**Ready to proceed?**
