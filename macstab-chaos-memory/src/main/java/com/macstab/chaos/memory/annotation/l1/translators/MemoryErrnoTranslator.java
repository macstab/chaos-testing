/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.translators;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.memory.CompositeMemoryChaos;
import com.macstab.chaos.memory.annotation.l1.MemoryErrnoBinding;
import com.macstab.chaos.memory.api.AdvancedMemoryChaos;
import com.macstab.chaos.memory.api.RuleHandle;
import com.macstab.chaos.memory.model.MemoryRule;

/**
 * Parameterised L1 translator for every memory-errno L1 annotation. Reads the per-annotation {@link
 * MemoryErrnoBinding} meta-annotation to recover the (selector, errno) tuple, then builds and
 * applies the corresponding {@link MemoryRule} via {@link AdvancedMemoryChaos}.
 *
 * <p>One translator instance handles all 45 memory-errno L1 annotations — they differ only in their
 * {@link MemoryErrnoBinding} values and the {@code probability} attribute. The lookup is O(1)
 * (annotation metadata is class-bound and cached by the JVM).
 *
 * <p>Backend selection is deferred to {@link CompositeMemoryChaos#standard()}, which honours the
 * existing libchaos / cgroups strategy precedence. If no backend can honour memory chaos on the
 * given container the call surfaces {@link
 * com.macstab.chaos.core.exception.LibchaosNotPreparedException} or {@link
 * com.macstab.chaos.core.exception.ChaosUnsupportedOperationException} — the chaos-core {@code
 * L1AnnotationProcessor} catches both and routes through the annotation's {@code OnMissingEnv}
 * attribute.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class MemoryErrnoTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the reflective L1 translator contract. */
  public MemoryErrnoTranslator() {}

  @Override
  public Object apply(final GenericContainer<?> container, final Annotation annotation) {
    final MemoryRule rule = buildRule(annotation);
    final AdvancedMemoryChaos adv = CompositeMemoryChaos.standard().advanced();
    return adv.apply(container, rule);
  }

  /**
   * Build the {@link MemoryRule} encoded by {@code annotation} without applying it. Exposed
   * package-private so unit tests can assert the (selector, errno, probability) tuple without
   * needing a running container.
   *
   * @param annotation any L1 annotation carrying {@link MemoryErrnoBinding}
   * @return the rule the translator would apply
   * @throws IllegalStateException if {@link MemoryErrnoBinding} is missing
   */
  static MemoryRule buildRule(final Annotation annotation) {
    final MemoryErrnoBinding binding =
        annotation.annotationType().getAnnotation(MemoryErrnoBinding.class);
    if (binding == null) {
      throw new IllegalStateException(
          "@MemoryErrnoBinding meta-annotation missing on "
              + annotation.annotationType().getName()
              + " — every memory-errno L1 annotation must declare its (selector, errno) tuple.");
    }
    final double probability = readProbability(annotation);
    return MemoryRule.errno(binding.selector(), binding.errno(), probability);
  }

  @Override
  public void remove(final GenericContainer<?> container, final Object handle) {
    if (!(handle instanceof RuleHandle ruleHandle)) {
      // Defensive — handle must be a memory RuleHandle. Silent on type mismatch (best-effort
      // contract).
      return;
    }
    CompositeMemoryChaos.standard().advanced().remove(container, ruleHandle);
  }

  private static double readProbability(final Annotation annotation) {
    try {
      final Method m = annotation.annotationType().getMethod("probability");
      final Object v = m.invoke(annotation);
      return v instanceof Double d ? d : 1.0;
    } catch (final NoSuchMethodException e) {
      return 1.0; // defensive — every L1 annotation declares probability(), but tolerate absence
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(
          "Failed to read probability() from " + annotation.annotationType().getName(), e);
    }
  }
}
