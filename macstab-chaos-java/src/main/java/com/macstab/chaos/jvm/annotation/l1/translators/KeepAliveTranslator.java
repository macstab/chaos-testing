/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.translators;

import java.lang.annotation.Annotation;
import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;

/** Stressor L1 translator: keep-alive thread that refuses to terminate. */
public final class KeepAliveTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public KeepAliveTranslator() {}

  @Override
  public Object apply(final GenericContainer<?> container, final Annotation annotation) {
    final String threadName =
        JvmL1Translators.readString(annotation, "threadName", "chaos-l1-keepalive");
    final boolean daemon = JvmL1Translators.readBoolean(annotation, "daemon", true);
    final long heartbeatMs = JvmL1Translators.readLong(annotation, "heartbeatMs", 1000L);
    return JvmL1Translators.buildStressorScenarioAndPush(
        container,
        annotation,
        ChaosSelector.stress(ChaosSelector.StressTarget.KEEPALIVE),
        ChaosEffect.keepAlive(threadName, daemon, Duration.ofMillis(heartbeatMs)));
  }

  @Override
  public void remove(final GenericContainer<?> container, final Object handle) {
    JvmL1Translators.removeScenario(container, handle);
  }
}
