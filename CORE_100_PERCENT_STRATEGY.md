# STRATEGY: 100% Coverage on Core Packages

**Date:** 2026-03-26 21:10 CET  
**Goal:** Get extension, platform/linux, shell, facade, spi to ~100%  
**Approach:** Surgical - target ONLY uncovered lines

---

## CURRENT STATUS (68.4% overall):

**MUST REACH ~100%:**
1. ✅ command/network: 100% (DONE)
2. ✅ command/process: 100% (DONE)
3. ✅ platform: 93.7% (close)
4. ✅ command/http: 89.6% (acceptable)
5. ✅ util: 89.5% (acceptable)
6. ✅ extension/internal: 87.3% (acceptable)
7. ✅ api: 84.8% (DONE - Phase 3)
8. ⚠️ shell: 75.6% → TARGET 95%+
9. ⚠️ spi: 68.6% → TARGET 95%+
10. ⚠️ extension: 63.4% → TARGET 95%+
11. ⚠️ facade: 62.4% → TARGET 95%+
12. ⚠️ platform/linux: 52.0% → TARGET 95%+

---

## STRATEGY:

### Phase 1: Generate Detailed Coverage Report (RUNNING)
- Full test run + JaCoCo report
- Identify EXACT uncovered methods/lines
- Prioritize by impact

### Phase 2: Surgical Testing (6-8 hours)
For each package:
1. Open JaCoCo HTML report for package
2. Identify red (uncovered) lines
3. Write MINIMAL test to hit those lines
4. Re-run coverage
5. Repeat until 95%+

### Phase 3: Final Validation
- Full test suite
- Coverage report
- Verify 95%+ on all 5 core packages

---

## TACTICAL APPROACH:

**DON'T:** Write comprehensive test suites  
**DO:** Write laser-focused tests for uncovered lines

**DON'T:** Test every edge case  
**DO:** Hit every code branch once

**DON'T:** Perfect test organization  
**DO:** Fast iteration - add tests, run, check, repeat

---

## ESTIMATED EFFORT:

- platform/linux (52% → 95%): 2 hours (43% gap)
- extension (63% → 95%): 2 hours (32% gap)
- facade (62% → 95%): 1.5 hours (33% gap)
- shell (76% → 95%): 1 hour (19% gap)
- spi (69% → 95%): 1 hour (26% gap)

**Total:** 7.5 hours (aggressive)

---

## NEXT STEPS:

1. Wait for coverage report generation
2. Open HTML reports for each package
3. Start with platform/linux (biggest gap)
4. Work down the list
5. Commit after each package reaches 95%

---

*Strategy created: 2026-03-26 21:10 CET*  
*Target: 100% on core packages (95%+ acceptable)*  
*Approach: Surgical, minimal, fast*
