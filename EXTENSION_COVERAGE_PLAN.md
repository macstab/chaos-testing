# ChaosTestingExtension - Path to 90%

**Current:** 39% (756 uncovered instructions)  
**Target:** 90% (need 636 instructions covered)  
**Gap:** 51%

## STRATEGY:

The big gaps are likely:
1. beforeAll() - container lifecycle + plugin orchestration
2. afterAll() - cleanup + resource management
3. Parameter resolution edge cases (already partially covered)
4. ThreadLocal registry management (partially covered)

## FAST PATH TO 90%:

Write integration tests that exercise:
1. **Full lifecycle** (beforeAll → test → afterAll)
2. **Plugin loading + container creation**
3. **Resource constraints application**
4. **Error paths in lifecycle**

These will hit MANY uncovered branches at once because they exercise the full orchestration.

## TOKEN BUDGET: 70k remaining

Enough for comprehensive tests.
