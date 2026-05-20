/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.translators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.Annotation;
import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.macstab.chaos.jvm.annotation.l1.async.ChaosAsyncCompleteExceptionalCompletion;
import com.macstab.chaos.jvm.annotation.l1.async.ChaosAsyncCompleteSuppress;
import com.macstab.chaos.jvm.annotation.l1.dns.ChaosDnsResolveInjectException;
import com.macstab.chaos.jvm.annotation.l1.executor.ChaosExecutorAwaitTerminationGate;
import com.macstab.chaos.jvm.annotation.l1.http_client.ChaosHttpClientSendDelay;
import com.macstab.chaos.jvm.annotation.l1.http_client.ChaosHttpClientSendGate;
import com.macstab.chaos.jvm.annotation.l1.http_client.ChaosHttpClientSendReject;
import com.macstab.chaos.jvm.annotation.l1.jdbc.ChaosJdbcConnectionAcquireDelay;
import com.macstab.chaos.jvm.annotation.l1.jdbc.ChaosJdbcStatementExecuteInjectException;
import com.macstab.chaos.jvm.annotation.l1.jvm_runtime.ChaosInstantNowSkew;
import com.macstab.chaos.jvm.annotation.l1.jvm_runtime.ChaosSystemGcRequestSuppress;
import com.macstab.chaos.jvm.annotation.l1.method.ChaosMethodEnterInjectException;
import com.macstab.chaos.jvm.annotation.l1.method.ChaosMethodExitCorruptReturn;
import com.macstab.chaos.jvm.annotation.l1.monitor.ChaosMonitorEnterGate;
import com.macstab.chaos.jvm.annotation.l1.network.ChaosSocketCloseInjectException;
import com.macstab.chaos.jvm.annotation.l1.network.ChaosSocketConnectReject;
import com.macstab.chaos.jvm.annotation.l1.nio.ChaosNioSelectorSelectSpuriousWakeup;
import com.macstab.chaos.jvm.annotation.l1.queue.ChaosQueueTakeGate;
import com.macstab.chaos.jvm.annotation.l1.stressors.ChaosCodeCachePressure;
import com.macstab.chaos.jvm.annotation.l1.stressors.ChaosDeadlock;
import com.macstab.chaos.jvm.annotation.l1.stressors.ChaosFinalizerBacklog;
import com.macstab.chaos.jvm.annotation.l1.stressors.ChaosGcPressure;
import com.macstab.chaos.jvm.annotation.l1.stressors.ChaosHeapPressure;
import com.macstab.chaos.jvm.annotation.l1.stressors.ChaosMetaspacePressure;
import com.macstab.chaos.jvm.annotation.l1.stressors.ChaosMonitorContention;
import com.macstab.chaos.jvm.annotation.l1.stressors.ChaosReferenceQueueFlood;
import com.macstab.chaos.jvm.annotation.l1.stressors.ChaosSafepointStorm;
import com.macstab.chaos.jvm.annotation.l1.stressors.ChaosStringInternPressure;
import com.macstab.chaos.jvm.annotation.l1.stressors.ChaosThreadLeak;
import com.macstab.chaos.jvm.annotation.l1.stressors.ChaosThreadLocalLeak;
import com.macstab.chaos.jvm.annotation.l1.stressors.ChaosVirtualThreadCarrierPinning;
import com.macstab.chaos.jvm.annotation.l1.thread.ChaosThreadStartDelay;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * Table-driven test of every effect-family translator. Uses the package-private
 * {@code JvmL1Translators.buildXxxScenario} helpers so the constructed {@link ChaosScenario} is
 * inspected directly — bypasses the {@code JvmPlanAccumulator} push that requires a working
 * container (covered separately by {@code JavaAgentTransportIntegrationTest}).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("JVM L1 translators — table-driven scenario construction")
class JvmL1TranslatorsTest {

  // ==================== fixtures ====================

  @ChaosJdbcConnectionAcquireDelay(delayMs = 250L, maxDelayMs = 500L)
  static class JdbcDelay250To500 {}

  @ChaosJdbcStatementExecuteInjectException(exceptionClassName = "java.sql.SQLException", message = "boom")
  static class JdbcSqlException {}

  @ChaosHttpClientSendDelay
  static class HttpDelayDefault {}

  @ChaosHttpClientSendReject(message = "circuit-broken")
  static class HttpReject {}

  @ChaosHttpClientSendGate(maxBlockMs = 60_000L)
  static class HttpGate60s {}

  @ChaosInstantNowSkew(skewMs = -120_000L, mode = ChaosEffect.ClockSkewMode.FREEZE)
  static class ClockFreeze {}

  @ChaosSystemGcRequestSuppress
  static class GcSuppress {}

  @ChaosNioSelectorSelectSpuriousWakeup
  static class NioSpurious {}

  @ChaosAsyncCompleteSuppress
  static class AsyncSuppress {}

  @ChaosAsyncCompleteExceptionalCompletion(
      failureKind = ChaosEffect.FailureKind.TIMEOUT, message = "timeout via L1")
  static class AsyncTimeout {}

  @ChaosDnsResolveInjectException(exceptionClassName = "java.net.UnknownHostException", message = "no such host")
  static class DnsUnknownHost {}

  @ChaosThreadStartDelay(delayMs = 50L)
  static class ThreadStartDelay50 {}

  @ChaosMonitorEnterGate(maxBlockMs = 5_000L)
  static class MonitorGate5s {}

  @ChaosQueueTakeGate(maxBlockMs = 10_000L)
  static class QueueTakeGate10s {}

  @ChaosExecutorAwaitTerminationGate
  static class ExecutorAwaitGate {}

  @ChaosSocketConnectReject(message = "connection refused via L1")
  static class SocketConnectReject {}

  @ChaosSocketCloseInjectException(exceptionClassName = "java.io.IOException", message = "close fail")
  static class SocketCloseException {}

  // Method-selector fixtures
  @ChaosMethodEnterInjectException(
      classPattern = "com.example.service.",
      methodNamePattern = "save",
      exceptionClassName = "java.io.IOException",
      message = "method-entry chaos")
  static class MethodEnterIo {}

  @ChaosMethodExitCorruptReturn(
      classPattern = "com.example.dao.", strategy = ChaosEffect.ReturnValueStrategy.EMPTY)
  static class MethodExitEmpty {}

  @ChaosMethodEnterInjectException // no patterns -> should fail at construction time
  static class MethodEnterNoPatterns {}

  // Stressor fixtures (one per stressor)
  @ChaosHeapPressure(bytes = 16_777_216L, chunkSizeBytes = 524_288)
  static class HeapPressureSmall {}

  @ChaosThreadLeak(threadCount = 5, namePrefix = "leak-", daemon = true)
  static class ThreadLeakSmall {}

  @ChaosDeadlock(participantCount = 3)
  static class Deadlock3 {}

  @ChaosCodeCachePressure(classCount = 100, methodsPerClass = 10)
  static class CodeCacheSmall {}

  @ChaosMetaspacePressure(generatedClassCount = 500, fieldsPerClass = 5)
  static class MetaspaceSmall {}

  @ChaosGcPressure(allocationRateBytesPerSecond = 10_000_000L, durationMs = 5_000L)
  static class GcSmall {}

  @ChaosFinalizerBacklog(objectCount = 50, finalizerDelayMs = 25L)
  static class FinalizerSmall {}

  @ChaosThreadLocalLeak(entriesPerThread = 10, valueSizeBytes = 1024)
  static class ThreadLocalLeakSmall {}

  @ChaosMonitorContention(lockHoldMs = 20L, contendingThreadCount = 4)
  static class MonitorContentionSmall {}

  @ChaosSafepointStorm(gcIntervalMs = 200L)
  static class SafepointStormDefault {}

  @ChaosStringInternPressure(internCount = 500, stringLengthBytes = 32)
  static class StringInternSmall {}

  @ChaosReferenceQueueFlood(referenceCount = 100, floodIntervalMs = 200L)
  static class RefQueueSmall {}

  @ChaosVirtualThreadCarrierPinning(pinnedThreadCount = 2, pinDurationMs = 50L)
  static class VthreadPinSmall {}

  // ==================== helpers ====================

  private static Annotation pick(final Class<?> clazz) {
    for (final Annotation a : clazz.getAnnotations()) {
      if (a.annotationType().getName().startsWith("java.")) {
        continue;
      }
      return a;
    }
    throw new IllegalStateException("No annotation on " + clazz);
  }

  // ==================== interceptor effect coverage ====================

  static Stream<Arguments> interceptors() {
    return Stream.of(
        // (fixture, expected selector class, expected effect class, OperationType)
        Arguments.of(
            JdbcDelay250To500.class,
            ChaosSelector.JdbcSelector.class,
            ChaosEffect.DelayEffect.class,
            OperationType.JDBC_CONNECTION_ACQUIRE),
        Arguments.of(
            JdbcSqlException.class,
            ChaosSelector.JdbcSelector.class,
            ChaosEffect.ExceptionInjectionEffect.class,
            OperationType.JDBC_STATEMENT_EXECUTE),
        Arguments.of(
            HttpDelayDefault.class,
            ChaosSelector.HttpClientSelector.class,
            ChaosEffect.DelayEffect.class,
            OperationType.HTTP_CLIENT_SEND),
        Arguments.of(
            HttpReject.class,
            ChaosSelector.HttpClientSelector.class,
            ChaosEffect.RejectEffect.class,
            OperationType.HTTP_CLIENT_SEND),
        Arguments.of(
            HttpGate60s.class,
            ChaosSelector.HttpClientSelector.class,
            ChaosEffect.GateEffect.class,
            OperationType.HTTP_CLIENT_SEND),
        Arguments.of(
            ClockFreeze.class,
            ChaosSelector.JvmRuntimeSelector.class,
            ChaosEffect.ClockSkewEffect.class,
            OperationType.INSTANT_NOW),
        Arguments.of(
            GcSuppress.class,
            ChaosSelector.JvmRuntimeSelector.class,
            ChaosEffect.SuppressEffect.class,
            OperationType.SYSTEM_GC_REQUEST),
        Arguments.of(
            NioSpurious.class,
            ChaosSelector.NioSelector.class,
            ChaosEffect.SpuriousWakeupEffect.class,
            OperationType.NIO_SELECTOR_SELECT),
        Arguments.of(
            AsyncSuppress.class,
            ChaosSelector.AsyncSelector.class,
            ChaosEffect.SuppressEffect.class,
            OperationType.ASYNC_COMPLETE),
        Arguments.of(
            AsyncTimeout.class,
            ChaosSelector.AsyncSelector.class,
            ChaosEffect.ExceptionalCompletionEffect.class,
            OperationType.ASYNC_COMPLETE),
        Arguments.of(
            DnsUnknownHost.class,
            ChaosSelector.DnsSelector.class,
            ChaosEffect.ExceptionInjectionEffect.class,
            OperationType.DNS_RESOLVE),
        Arguments.of(
            ThreadStartDelay50.class,
            ChaosSelector.ThreadSelector.class,
            ChaosEffect.DelayEffect.class,
            OperationType.THREAD_START),
        Arguments.of(
            MonitorGate5s.class,
            ChaosSelector.MonitorSelector.class,
            ChaosEffect.GateEffect.class,
            OperationType.MONITOR_ENTER),
        Arguments.of(
            QueueTakeGate10s.class,
            ChaosSelector.QueueSelector.class,
            ChaosEffect.GateEffect.class,
            OperationType.QUEUE_TAKE),
        Arguments.of(
            ExecutorAwaitGate.class,
            ChaosSelector.ExecutorSelector.class,
            ChaosEffect.GateEffect.class,
            OperationType.EXECUTOR_AWAIT_TERMINATION),
        Arguments.of(
            SocketConnectReject.class,
            ChaosSelector.NetworkSelector.class,
            ChaosEffect.RejectEffect.class,
            OperationType.SOCKET_CONNECT),
        Arguments.of(
            SocketCloseException.class,
            ChaosSelector.NetworkSelector.class,
            ChaosEffect.ExceptionInjectionEffect.class,
            OperationType.SOCKET_CLOSE));
  }

  @ParameterizedTest(name = "{0} → ({1}, {3}, {2})")
  @MethodSource("interceptors")
  @DisplayName("each interceptor annotation builds the correct (selector, effect, operation) tuple")
  void interceptorsBuildTuple(
      final Class<?> fixture,
      final Class<? extends ChaosSelector> expectedSelector,
      final Class<? extends ChaosEffect> expectedEffect,
      final OperationType expectedOp) {
    final Annotation a = pick(fixture);
    final ChaosEffect effect = buildEffectFor(a);
    final ChaosScenario scenario = JvmL1Translators.buildInterceptorScenario(a, effect);

    assertThat(scenario.selector()).isInstanceOf(expectedSelector);
    assertThat(scenario.effect()).isInstanceOf(expectedEffect);
    assertThat(extractOperations(scenario.selector())).contains(expectedOp);
  }

  @Test
  @DisplayName("Delay translator: min/max propagated; 250..500ms range")
  void delayRangePropagated() {
    final ChaosScenario s =
        JvmL1Translators.buildInterceptorScenario(
            pick(JdbcDelay250To500.class), buildEffectFor(pick(JdbcDelay250To500.class)));
    final ChaosEffect.DelayEffect d = (ChaosEffect.DelayEffect) s.effect();
    assertThat(d.minDelay()).isEqualTo(Duration.ofMillis(250));
    assertThat(d.maxDelay()).isEqualTo(Duration.ofMillis(500));
  }

  @Test
  @DisplayName("ExceptionInjection: exceptionClassName + message propagated")
  void exceptionPayloadPropagated() {
    final ChaosScenario s =
        JvmL1Translators.buildInterceptorScenario(
            pick(JdbcSqlException.class), buildEffectFor(pick(JdbcSqlException.class)));
    final ChaosEffect.ExceptionInjectionEffect e = (ChaosEffect.ExceptionInjectionEffect) s.effect();
    assertThat(e.exceptionClassName()).isEqualTo("java.sql.SQLException");
    assertThat(e.message()).isEqualTo("boom");
  }

  @Test
  @DisplayName("ClockSkew: skewMs + mode propagated")
  void clockSkewPayloadPropagated() {
    final ChaosScenario s =
        JvmL1Translators.buildInterceptorScenario(
            pick(ClockFreeze.class), buildEffectFor(pick(ClockFreeze.class)));
    final ChaosEffect.ClockSkewEffect e = (ChaosEffect.ClockSkewEffect) s.effect();
    assertThat(e.mode()).isEqualTo(ChaosEffect.ClockSkewMode.FREEZE);
    assertThat(e.skewAmount()).isEqualTo(Duration.ofMillis(-120_000L));
  }

  // ==================== stressor coverage ====================

  static Stream<Arguments> stressors() {
    return Stream.of(
        Arguments.of(HeapPressureSmall.class, ChaosSelector.StressTarget.HEAP, ChaosEffect.HeapPressureEffect.class),
        Arguments.of(ThreadLeakSmall.class, ChaosSelector.StressTarget.THREAD_LEAK, ChaosEffect.ThreadLeakEffect.class),
        Arguments.of(Deadlock3.class, ChaosSelector.StressTarget.DEADLOCK, ChaosEffect.DeadlockEffect.class),
        Arguments.of(CodeCacheSmall.class, ChaosSelector.StressTarget.CODE_CACHE_PRESSURE, ChaosEffect.CodeCachePressureEffect.class),
        Arguments.of(MetaspaceSmall.class, ChaosSelector.StressTarget.METASPACE, ChaosEffect.MetaspacePressureEffect.class),
        Arguments.of(GcSmall.class, ChaosSelector.StressTarget.GC_PRESSURE, ChaosEffect.GcPressureEffect.class),
        Arguments.of(FinalizerSmall.class, ChaosSelector.StressTarget.FINALIZER_BACKLOG, ChaosEffect.FinalizerBacklogEffect.class),
        Arguments.of(ThreadLocalLeakSmall.class, ChaosSelector.StressTarget.THREAD_LOCAL_LEAK, ChaosEffect.ThreadLocalLeakEffect.class),
        Arguments.of(MonitorContentionSmall.class, ChaosSelector.StressTarget.MONITOR_CONTENTION, ChaosEffect.MonitorContentionEffect.class),
        Arguments.of(SafepointStormDefault.class, ChaosSelector.StressTarget.SAFEPOINT_STORM, ChaosEffect.SafepointStormEffect.class),
        Arguments.of(StringInternSmall.class, ChaosSelector.StressTarget.STRING_INTERN_PRESSURE, ChaosEffect.StringInternPressureEffect.class),
        Arguments.of(RefQueueSmall.class, ChaosSelector.StressTarget.REFERENCE_QUEUE_FLOOD, ChaosEffect.ReferenceQueueFloodEffect.class),
        Arguments.of(VthreadPinSmall.class, ChaosSelector.StressTarget.VIRTUAL_THREAD_CARRIER_PINNING, ChaosEffect.VirtualThreadCarrierPinningEffect.class));
  }

  @ParameterizedTest(name = "{0} → ({1}, {2})")
  @MethodSource("stressors")
  @DisplayName("each stressor annotation maps to its StressTarget + effect")
  void stressorsBuildTuple(
      final Class<?> fixture,
      final ChaosSelector.StressTarget expectedTarget,
      final Class<? extends ChaosEffect> expectedEffect) {
    final Annotation a = pick(fixture);
    final ChaosEffect effect = buildEffectFor(a);
    final ChaosSelector selector = ChaosSelector.stress(expectedTarget);
    final ChaosScenario scenario = JvmL1Translators.buildStressorScenario(a, selector, effect);
    assertThat(scenario.selector()).isInstanceOf(ChaosSelector.StressSelector.class);
    assertThat(((ChaosSelector.StressSelector) scenario.selector()).target()).isEqualTo(expectedTarget);
    assertThat(scenario.effect()).isInstanceOf(expectedEffect);
  }

  // ==================== method-selector coverage ====================

  @Nested
  @DisplayName("MethodSelector annotations")
  class MethodSelectorTests {

    @Test
    void methodEnterInjectExceptionBuildsCorrectScenario() {
      final Annotation a = pick(MethodEnterIo.class);
      final ChaosEffect effect = buildEffectFor(a);
      final ChaosScenario s = JvmL1Translators.buildMethodScenario(a, effect);
      assertThat(s.selector()).isInstanceOf(ChaosSelector.MethodSelector.class);
      final ChaosSelector.MethodSelector ms = (ChaosSelector.MethodSelector) s.selector();
      assertThat(ms.operations()).containsExactly(OperationType.METHOD_ENTER);
      assertThat(s.effect()).isInstanceOf(ChaosEffect.ExceptionInjectionEffect.class);
    }

    @Test
    void methodExitCorruptReturnBuildsCorrectScenario() {
      final Annotation a = pick(MethodExitEmpty.class);
      final ChaosEffect effect = buildEffectFor(a);
      final ChaosScenario s = JvmL1Translators.buildMethodScenario(a, effect);
      assertThat(s.selector()).isInstanceOf(ChaosSelector.MethodSelector.class);
      assertThat(((ChaosSelector.MethodSelector) s.selector()).operations())
          .containsExactly(OperationType.METHOD_EXIT);
      assertThat(s.effect()).isInstanceOf(ChaosEffect.ReturnValueCorruptionEffect.class);
      assertThat(((ChaosEffect.ReturnValueCorruptionEffect) s.effect()).strategy())
          .isEqualTo(ChaosEffect.ReturnValueStrategy.EMPTY);
    }

    @Test
    @DisplayName("MethodSelector annotation with both patterns blank → IllegalArgumentException")
    void rejectsAllAnyPatterns() {
      final Annotation a = pick(MethodEnterNoPatterns.class);
      final ChaosEffect effect = buildEffectFor(a);
      assertThatThrownBy(() -> JvmL1Translators.buildMethodScenario(a, effect))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("classPattern or methodNamePattern to be non-blank");
    }
  }

  // ==================== effect builder dispatch ====================

  /**
   * Reproduce the per-effect translator's construction step without invoking the accumulator.
   * The mapping must stay in sync with the translator classes — if a new effect is added, this
   * switch must learn it.
   */
  private static ChaosEffect buildEffectFor(final Annotation a) {
    final String tName =
        a.annotationType().getAnnotation(com.macstab.chaos.core.extension.ChaosL1.class).translator();
    return switch (tName.substring(tName.lastIndexOf('.') + 1)) {
      case "DelayTranslator" -> ChaosEffect.delay(
          Duration.ofMillis(JvmL1Translators.readLong(a, "delayMs", 100L)),
          Duration.ofMillis(
              JvmL1Translators.readLong(a, "maxDelayMs", JvmL1Translators.readLong(a, "delayMs", 100L))));
      case "RejectTranslator" -> ChaosEffect.reject(
          JvmL1Translators.readString(a, "message", "rejected"));
      case "SuppressTranslator" -> ChaosEffect.suppress();
      case "GateTranslator" -> ChaosEffect.gate(
          Duration.ofMillis(JvmL1Translators.readLong(a, "maxBlockMs", 30_000L)));
      case "ExceptionInjectionTranslator" -> ChaosEffect.injectException(
          JvmL1Translators.readString(a, "exceptionClassName", "java.io.IOException"),
          JvmL1Translators.readString(a, "message", "injected"));
      case "ClockSkewTranslator" -> ChaosEffect.skewClock(
          Duration.ofMillis(JvmL1Translators.readLong(a, "skewMs", -60_000L)),
          JvmL1Translators.readEnum(a, "mode", ChaosEffect.ClockSkewMode.FIXED));
      case "SpuriousWakeupTranslator" -> ChaosEffect.spuriousWakeup();
      case "ExceptionalCompletionTranslator" -> ChaosEffect.exceptionalCompletion(
          JvmL1Translators.readEnum(a, "failureKind", ChaosEffect.FailureKind.RUNTIME),
          JvmL1Translators.readString(a, "message", "completed exceptionally"));
      case "ReturnValueCorruptionTranslator" -> ChaosEffect.corruptReturnValue(
          JvmL1Translators.readEnum(a, "strategy", ChaosEffect.ReturnValueStrategy.NULL));
      case "HeapPressureTranslator" -> ChaosEffect.heapPressure(
          JvmL1Translators.readLong(a, "bytes", 64L * 1024L * 1024L),
          JvmL1Translators.readInt(a, "chunkSizeBytes", 1024 * 1024));
      case "ThreadLeakTranslator" -> ChaosEffect.threadLeak(
          JvmL1Translators.readInt(a, "threadCount", 50),
          JvmL1Translators.readString(a, "namePrefix", "chaos-l1-leaked-"),
          JvmL1Translators.readBoolean(a, "daemon", true));
      case "DeadlockTranslator" -> ChaosEffect.deadlock(
          JvmL1Translators.readInt(a, "participantCount", 2));
      case "CodeCachePressureTranslator" -> ChaosEffect.codeCachePressure(
          JvmL1Translators.readInt(a, "classCount", 5000),
          JvmL1Translators.readInt(a, "methodsPerClass", 50));
      case "MetaspacePressureTranslator" -> ChaosEffect.metaspacePressure(
          JvmL1Translators.readInt(a, "generatedClassCount", 10_000),
          JvmL1Translators.readInt(a, "fieldsPerClass", 10));
      case "GcPressureTranslator" -> ChaosEffect.gcPressure(
          JvmL1Translators.readLong(a, "allocationRateBytesPerSecond", 104_857_600L),
          Duration.ofMillis(JvmL1Translators.readLong(a, "durationMs", 60_000L)));
      case "FinalizerBacklogTranslator" -> ChaosEffect.finalizerBacklog(
          JvmL1Translators.readInt(a, "objectCount", 1000),
          Duration.ofMillis(JvmL1Translators.readLong(a, "finalizerDelayMs", 100L)));
      case "ThreadLocalLeakTranslator" -> ChaosEffect.threadLocalLeak(
          JvmL1Translators.readInt(a, "entriesPerThread", 100),
          JvmL1Translators.readInt(a, "valueSizeBytes", 65_536));
      case "MonitorContentionTranslator" -> ChaosEffect.monitorContention(
          Duration.ofMillis(JvmL1Translators.readLong(a, "lockHoldMs", 50L)),
          JvmL1Translators.readInt(a, "contendingThreadCount", 8));
      case "SafepointStormTranslator" -> ChaosEffect.safepointStorm(
          Duration.ofMillis(JvmL1Translators.readLong(a, "gcIntervalMs", 100L)));
      case "StringInternPressureTranslator" -> ChaosEffect.stringInternPressure(
          JvmL1Translators.readInt(a, "internCount", 100_000),
          JvmL1Translators.readInt(a, "stringLengthBytes", 64));
      case "ReferenceQueueFloodTranslator" -> ChaosEffect.referenceQueueFlood(
          JvmL1Translators.readInt(a, "referenceCount", 10_000),
          Duration.ofMillis(JvmL1Translators.readLong(a, "floodIntervalMs", 100L)));
      case "VirtualThreadCarrierPinningTranslator" -> ChaosEffect.virtualThreadCarrierPinning(
          JvmL1Translators.readInt(a, "pinnedThreadCount", 4),
          Duration.ofMillis(JvmL1Translators.readLong(a, "pinDurationMs", 100L)));
      case "KeepAliveTranslator" -> ChaosEffect.keepAlive(
          JvmL1Translators.readString(a, "threadName", "chaos-l1-keepalive"),
          JvmL1Translators.readBoolean(a, "daemon", true),
          Duration.ofMillis(JvmL1Translators.readLong(a, "heartbeatMs", 1000L)));
      case "DirectBufferPressureTranslator" -> ChaosEffect.directBufferPressure(
          JvmL1Translators.readLong(a, "totalBytes", 256L * 1024L * 1024L),
          JvmL1Translators.readInt(a, "bufferSizeBytes", 1024 * 1024));
      default -> throw new IllegalStateException("Unknown translator: " + tName);
    };
  }

  private static java.util.Set<OperationType> extractOperations(final ChaosSelector s) {
    // All selectors except StressSelector expose operations(); reflective probe keeps the test
    // schema-agnostic across sealed-type changes.
    try {
      final java.lang.reflect.Method m = s.getClass().getMethod("operations");
      @SuppressWarnings("unchecked")
      final java.util.Set<OperationType> ops = (java.util.Set<OperationType>) m.invoke(s);
      return ops;
    } catch (final ReflectiveOperationException e) {
      return java.util.Set.of();
    }
  }
}
