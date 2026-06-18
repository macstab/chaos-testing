/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L1 chaos primitives for libchaos-memory. One annotation per legal (selector x errno x effect)
 * tuple per {@link com.macstab.chaos.memory.model.MemorySelector#validErrnos()}; annotations are
 * grouped by selector into sub-packages ({@code mmap}, {@code mmap_anon}, {@code mmap_file}, {@code
 * munmap}, {@code mprotect}, {@code madvise}, {@code wildcard}) so IDE autocomplete shows only the
 * family the developer is currently working with.
 *
 * <p>Each L1 annotation:
 *
 * <ul>
 *   <li>Is {@code @Target}-ed at both {@code TYPE} and {@code METHOD} — class-level annotations
 *       apply for the whole test class lifetime, method-level annotations apply for a single
 *       {@code @Test} invocation.
 *   <li>Is {@code @Repeatable} so the same annotation type can appear multiple times on one test
 *       class (e.g. different probabilities for different containers identified by {@code id}).
 *   <li>Carries {@code probability}, {@code id}, and {@code onMissingEnv} attributes; selector and
 *       errno are NOT attributes — they are part of the annotation name.
 *   <li>Is bound to either {@link
 *       com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator} or {@link
 *       com.macstab.chaos.memory.annotation.l1.translators.MemoryLatencyTranslator} via its
 *       {@code @ChaosL1(translator = "...")} meta-annotation, and to a (selector, errno) or
 *       (selector) tuple via its {@link MemoryErrnoBinding} or {@link MemoryLatencyBinding}
 *       meta-annotation.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.memory.annotation.l1;
