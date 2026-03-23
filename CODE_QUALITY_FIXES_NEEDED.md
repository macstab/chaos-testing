# Code Quality Fixes Needed - ALL MODULES

**Date:** 2026-03-22  
**Status:** CRITICAL ISSUES FOUND

---

## ✅ FIXED (3 modules)

1. **macstab-chaos-connection** ✅
   - Error handling (check all curl exit codes)
   - No Thread.sleep (use polling with timeout)
   - Port collision detection
   - iptables cleanup in reset()
   - Input validation (TargetAddress record)
   - Input sanitization (prevent injection)
   - Configurable timeouts

2. **macstab-chaos-cache** ✅
   - Error handling (check curl/redis-cli exit codes)
   - Polling with timeout for Toxiproxy startup
   - iptables cleanup in reset()
   - Configurable constants

3. **macstab-chaos-filesystem** ✅
   - Error handling (check dd/chmod exit codes)
   - Path validation (prevent traversal)
   - Size validation (format + limits)
   - Cleanup verification

---

## ❌ NEEDS FIXING (7 modules)

### 4. macstab-chaos-dns

**CRITICAL ISSUES:**
```java
// LINE 117: INJECTION VULNERABILITY!
container.execInContainer("sh", "-c", "echo '" + hostname + "' >> " + CHAOS_SERVFAIL_FILE);
// If hostname = "'; rm -rf /; echo '" → DISASTER!
```

**Fixes needed:**
- [ ] Sanitize hostname input (validate DNS format)
- [ ] Check execInContainer exit codes
- [ ] Validate file write success
- [ ] Clean up CoreDNS process in reset()
- [ ] Remove iptables rules in reset()
- [ ] Configurable CoreDNS port/paths
- [ ] Polling for CoreDNS startup (not hardcoded 500ms)

**Estimated effort:** 2-3 hours

---

### 5. macstab-chaos-time

**Issues:**
- No exit code checking on libfaketime commands
- Thread.sleep without timeout
- No validation of timestamp format
- No cleanup of /tmp/faketime file
- LD_PRELOAD env var not removed in reset()

**Fixes needed:**
- [ ] Check all exec exit codes
- [ ] Validate timestamp format
- [ ] Clean up files + env vars in reset()
- [ ] Configurable paths

**Estimated effort:** 1-2 hours

---

### 6. macstab-chaos-cpu

**Issues:**
- No exit code checking
- No validation of percentage (1-100)
- pkill may fail silently
- stress-ng process orphaned if reset() fails

**Fixes needed:**
- [ ] Check all exec exit codes
- [ ] Validate percentage range
- [ ] Robust process cleanup
- [ ] Configurable stress-ng params

**Estimated effort:** 1-2 hours

---

### 7. macstab-chaos-memory

**Issues:**
- No size validation (what if size = "999999999G"?)
- No exit code checking
- stress-ng orphaned processes

**Fixes needed:**
- [ ] Validate size format + limits
- [ ] Check all exec exit codes
- [ ] Robust cleanup

**Estimated effort:** 1-2 hours

---

### 8. macstab-chaos-disk

**Issues:**
- No size validation
- No exit code checking
- dd may fill disk completely (crash container!)

**Fixes needed:**
- [ ] Validate size limits (prevent container crash)
- [ ] Check all exec exit codes
- [ ] Verify cleanup success

**Estimated effort:** 1-2 hours

---

### 9. macstab-chaos-process

**Issues:**
- No signal validation
- No PID validation
- kill command may fail silently

**Fixes needed:**
- [ ] Validate signal enum
- [ ] Validate PID > 0
- [ ] Check exec exit codes

**Estimated effort:** 1 hour

---

### 10. macstab-chaos-network

**Issues:**
- No bandwidth validation
- No packet loss rate validation (0-1)
- tc commands may fail silently
- iptables rules not cleaned up

**Fixes needed:**
- [ ] Validate all numeric inputs
- [ ] Check tc/iptables exit codes
- [ ] Clean up tc qdiscs in reset()
- [ ] Remove iptables rules in reset()

**Estimated effort:** 2-3 hours

---

## 📊 TOTAL EFFORT ESTIMATE

| Module | Status | Effort |
|--------|--------|--------|
| Connection | ✅ Fixed | Done |
| Cache | ✅ Fixed | Done |
| Filesystem | ✅ Fixed | Done |
| **DNS** | ❌ Critical | 2-3h |
| **Time** | ❌ Medium | 1-2h |
| **CPU** | ❌ Medium | 1-2h |
| **Memory** | ❌ Medium | 1-2h |
| **Disk** | ❌ Medium | 1-2h |
| **Process** | ❌ Low | 1h |
| **Network** | ❌ Medium | 2-3h |

**TOTAL REMAINING:** 12-18 hours

---

## 🎯 PRIORITY ORDER

1. **DNS** (CRITICAL - injection vulnerability)
2. **Network** (iptables cleanup missing)
3. **Time** (LD_PRELOAD cleanup missing)
4. **CPU, Memory, Disk** (basic error handling)
5. **Process** (simple validation)

---

## ⚠️ RECOMMENDATION

**DO NOT RELEASE** until ALL 10 modules are fixed to Distinguished Engineer standards.

**Options:**
1. **Fix all now** (12-18 hours work)
2. **Ship only 3 fixed modules** (Connection, Cache, Filesystem)
3. **Mark others as experimental** until fixed

---

*Updated: 2026-03-22 17:52 GMT+1*
