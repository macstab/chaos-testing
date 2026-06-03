/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.testpack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.api.AdvancedConnectionChaos;
import com.macstab.chaos.connection.api.RuleHandle;
import com.macstab.chaos.connection.model.Effect;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.connection.testpack.composers.AcceptStormComposer;
import com.macstab.chaos.connection.testpack.composers.ConnectionRefusedComposer;
import com.macstab.chaos.connection.testpack.composers.HalfOpenConnectionComposer;
import com.macstab.chaos.connection.testpack.composers.PollTimeoutComposer;
import com.macstab.chaos.connection.testpack.composers.PortAlreadyInUseComposer;
import com.macstab.chaos.connection.testpack.composers.SendBufferStarvationComposer;
import com.macstab.chaos.connection.testpack.composers.SlowDownstreamComposer;
import com.macstab.chaos.connection.testpack.composers.SocketEphemeralExhaustionComposer;
import com.macstab.chaos.connection.testpack.composers.TcpResetStormComposer;
import com.macstab.chaos.connection.testpack.composers.ThunderingHerdComposer;
import com.macstab.chaos.connection.testpack.composers.UnreachableHostComposer;
import com.macstab.chaos.connection.testpack.composers.UnreachableNetworkComposer;
import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.extension.Severity;

@DisplayName("Connection L2 composers — contract and rule-building")
class ConnectionComposersTest {

  // ── Annotation contract helpers ──────────────────────────────────────────

  private static ChaosL2 chaosL2(final Class<?> annotationType) {
    final ChaosL2 meta = annotationType.getAnnotation(ChaosL2.class);
    assertThat(meta).as("@ChaosL2 missing on " + annotationType.getSimpleName()).isNotNull();
    return meta;
  }

  // ── Shared mock plumbing ─────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private static GenericContainer<?> container() {
    return mock(GenericContainer.class);
  }

  private static RuleHandle handle() {
    return mock(RuleHandle.class);
  }

  private static AdvancedConnectionChaos advMock(
      final CompositeConnectionChaos composite, final GenericContainer<?> container,
      final RuleHandle handle) {
    final AdvancedConnectionChaos adv = mock(AdvancedConnectionChaos.class);
    when(composite.advanced()).thenReturn(adv);
    when(adv.apply(eq(container), any(NetRule.class))).thenReturn(handle);
    return adv;
  }

  // ── NetRule argThat matchers ──────────────────────────────────────────────

  private static NetRule argErrnoRule(final NetOperation expectedOp, final Errno expectedErrno) {
    return org.mockito.ArgumentMatchers.argThat(rule -> {
      if (!(rule.effect() instanceof Effect.ErrnoFault ef)) return false;
      return rule.operation() == expectedOp
          && ef.errno() == expectedErrno
          && rule.endpoint() instanceof Endpoint.Wildcard;
    });
  }

  private static NetRule argLatencyRule(final NetOperation expectedOp, final Duration expectedDelay) {
    return org.mockito.ArgumentMatchers.argThat(rule -> {
      if (!(rule.effect() instanceof Effect.Latency lat)) return false;
      return rule.operation() == expectedOp
          && lat.delay().equals(expectedDelay)
          && rule.endpoint() instanceof Endpoint.Wildcard;
    });
  }

  private static NetRule argCorruptRule(final double expectedRate) {
    return org.mockito.ArgumentMatchers.argThat(rule -> {
      if (!(rule.effect() instanceof Effect.Corrupt c)) return false;
      return rule.operation() == NetOperation.RECV
          && Double.compare(c.rate(), expectedRate) == 0
          && rule.endpoint() instanceof Endpoint.Wildcard;
    });
  }

  private static NetRule argTimeoutRule(final Duration expectedDuration) {
    return org.mockito.ArgumentMatchers.argThat(rule -> {
      if (!(rule.effect() instanceof Effect.Timeout t)) return false;
      return rule.operation() == NetOperation.POLL
          && t.duration().equals(expectedDuration)
          && rule.endpoint() instanceof Endpoint.Wildcard;
    });
  }

  // ── Annotation fixture helper ─────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private static <A extends java.lang.annotation.Annotation> A fixture(
      final Class<?> holder, final Class<A> type) {
    final A ann = holder.getAnnotation(type);
    assertThat(ann).as(type.getSimpleName() + " missing on " + holder.getSimpleName()).isNotNull();
    return ann;
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ConnectionRefused
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosConnectionRefused")
  class ConnectionRefusedTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosConnectionRefused.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("ConnectionRefusedComposer");
    }

    @Test
    @DisplayName("endpoint() defaults to \"*\"")
    void annotation_has_wildcard_endpoint_by_default() throws Exception {
      var ann = ConnectionRefusedFixture.class.getAnnotation(CompositeChaosConnectionRefused.class);
      assertThat(ann.endpoint()).isEqualTo("*");
    }

    @Test
    @DisplayName("describe() returns non-empty list mentioning ECONNREFUSED")
    void describe() {
      final CompositeChaosConnectionRefused ann =
          fixture(ConnectionRefusedFixture.class, CompositeChaosConnectionRefused.class);
      final List<String> lines = new ConnectionRefusedComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines)).containsIgnoringCase("ECONNREFUSED");
    }

    @Test
    @DisplayName("apply() builds ECONNREFUSED errno rule on CONNECT with wildcard endpoint")
    void apply() {
      final CompositeChaosConnectionRefused ann =
          fixture(ConnectionRefusedFixture.class, CompositeChaosConnectionRefused.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final CompositeConnectionChaos composite = mock(CompositeConnectionChaos.class);
      advMock(composite, c, h);

      try (final MockedStatic<CompositeConnectionChaos> mocked =
          mockStatic(CompositeConnectionChaos.class)) {
        mocked.when(CompositeConnectionChaos::standard).thenReturn(composite);
        final List<Object> handles = new ConnectionRefusedComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        org.mockito.Mockito.verify(composite.advanced())
            .apply(eq(c), argErrnoRule(NetOperation.CONNECT, Errno.ECONNREFUSED));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new ConnectionRefusedComposer().removeAll(container(), List.of());
    }

    @CompositeChaosConnectionRefused
    static class ConnectionRefusedFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ThunderingHerd
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosThunderingHerd")
  class ThunderingHerdTests {

    @Test
    @DisplayName("annotation carries CRITICAL + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosThunderingHerd.class);
      assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
      assertThat(meta.composer()).endsWith("ThunderingHerdComposer");
    }

    @Test
    @DisplayName("endpoint() defaults to \"*\"")
    void annotation_has_wildcard_endpoint_by_default() throws Exception {
      var ann = ThunderingHerdFixture.class.getAnnotation(CompositeChaosThunderingHerd.class);
      assertThat(ann.endpoint()).isEqualTo("*");
    }

    @Test
    @DisplayName("describe() returns non-empty list mentioning ECONNREFUSED")
    void describe() {
      final CompositeChaosThunderingHerd ann =
          fixture(ThunderingHerdFixture.class, CompositeChaosThunderingHerd.class);
      final List<String> lines = new ThunderingHerdComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines)).containsIgnoringCase("ECONNREFUSED");
    }

    @Test
    @DisplayName("apply() builds ECONNREFUSED errno rule on CONNECT with wildcard endpoint")
    void apply() {
      final CompositeChaosThunderingHerd ann =
          fixture(ThunderingHerdFixture.class, CompositeChaosThunderingHerd.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final CompositeConnectionChaos composite = mock(CompositeConnectionChaos.class);
      advMock(composite, c, h);

      try (final MockedStatic<CompositeConnectionChaos> mocked =
          mockStatic(CompositeConnectionChaos.class)) {
        mocked.when(CompositeConnectionChaos::standard).thenReturn(composite);
        final List<Object> handles = new ThunderingHerdComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        org.mockito.Mockito.verify(composite.advanced())
            .apply(eq(c), argErrnoRule(NetOperation.CONNECT, Errno.ECONNREFUSED));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new ThunderingHerdComposer().removeAll(container(), List.of());
    }

    @CompositeChaosThunderingHerd
    static class ThunderingHerdFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // UnreachableHost
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosUnreachableHost")
  class UnreachableHostTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosUnreachableHost.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("UnreachableHostComposer");
    }

    @Test
    @DisplayName("endpoint() defaults to \"*\"")
    void annotation_has_wildcard_endpoint_by_default() throws Exception {
      var ann = UnreachableHostFixture.class.getAnnotation(CompositeChaosUnreachableHost.class);
      assertThat(ann.endpoint()).isEqualTo("*");
    }

    @Test
    @DisplayName("describe() returns non-empty list mentioning EHOSTUNREACH")
    void describe() {
      final CompositeChaosUnreachableHost ann =
          fixture(UnreachableHostFixture.class, CompositeChaosUnreachableHost.class);
      final List<String> lines = new UnreachableHostComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines)).containsIgnoringCase("EHOSTUNREACH");
    }

    @Test
    @DisplayName("apply() builds EHOSTUNREACH errno rule on CONNECT with wildcard endpoint")
    void apply() {
      final CompositeChaosUnreachableHost ann =
          fixture(UnreachableHostFixture.class, CompositeChaosUnreachableHost.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final CompositeConnectionChaos composite = mock(CompositeConnectionChaos.class);
      advMock(composite, c, h);

      try (final MockedStatic<CompositeConnectionChaos> mocked =
          mockStatic(CompositeConnectionChaos.class)) {
        mocked.when(CompositeConnectionChaos::standard).thenReturn(composite);
        final List<Object> handles = new UnreachableHostComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        org.mockito.Mockito.verify(composite.advanced())
            .apply(eq(c), argErrnoRule(NetOperation.CONNECT, Errno.EHOSTUNREACH));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new UnreachableHostComposer().removeAll(container(), List.of());
    }

    @CompositeChaosUnreachableHost
    static class UnreachableHostFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // UnreachableNetwork
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosUnreachableNetwork")
  class UnreachableNetworkTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosUnreachableNetwork.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("UnreachableNetworkComposer");
    }

    @Test
    @DisplayName("endpoint() defaults to \"*\"")
    void annotation_has_wildcard_endpoint_by_default() throws Exception {
      var ann = UnreachableNetworkFixture.class.getAnnotation(CompositeChaosUnreachableNetwork.class);
      assertThat(ann.endpoint()).isEqualTo("*");
    }

    @Test
    @DisplayName("describe() returns non-empty list mentioning ENETUNREACH")
    void describe() {
      final CompositeChaosUnreachableNetwork ann =
          fixture(UnreachableNetworkFixture.class, CompositeChaosUnreachableNetwork.class);
      final List<String> lines = new UnreachableNetworkComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines)).containsIgnoringCase("ENETUNREACH");
    }

    @Test
    @DisplayName("apply() builds ENETUNREACH errno rule on CONNECT with wildcard endpoint")
    void apply() {
      final CompositeChaosUnreachableNetwork ann =
          fixture(UnreachableNetworkFixture.class, CompositeChaosUnreachableNetwork.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final CompositeConnectionChaos composite = mock(CompositeConnectionChaos.class);
      advMock(composite, c, h);

      try (final MockedStatic<CompositeConnectionChaos> mocked =
          mockStatic(CompositeConnectionChaos.class)) {
        mocked.when(CompositeConnectionChaos::standard).thenReturn(composite);
        final List<Object> handles = new UnreachableNetworkComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        org.mockito.Mockito.verify(composite.advanced())
            .apply(eq(c), argErrnoRule(NetOperation.CONNECT, Errno.ENETUNREACH));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new UnreachableNetworkComposer().removeAll(container(), List.of());
    }

    @CompositeChaosUnreachableNetwork
    static class UnreachableNetworkFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // TcpResetStorm
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosTcpResetStorm")
  class TcpResetStormTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN + default rate 0.3")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosTcpResetStorm.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("TcpResetStormComposer");
      final CompositeChaosTcpResetStorm ann =
          fixture(TcpResetStormFixture.class, CompositeChaosTcpResetStorm.class);
      assertThat(ann.rate()).isEqualTo(0.3);
    }

    @Test
    @DisplayName("endpoint() defaults to \"*\"")
    void annotation_has_wildcard_endpoint_by_default() throws Exception {
      var ann = TcpResetStormFixture.class.getAnnotation(CompositeChaosTcpResetStorm.class);
      assertThat(ann.endpoint()).isEqualTo("*");
    }

    @Test
    @DisplayName("describe() returns non-empty list mentioning rate")
    void describe() {
      final CompositeChaosTcpResetStorm ann =
          fixture(TcpResetStormFixture.class, CompositeChaosTcpResetStorm.class);
      final List<String> lines = new TcpResetStormComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines)).contains("0.3");
    }

    @Test
    @DisplayName("apply() builds CORRUPT rule on RECV with rate=0.3 and wildcard endpoint")
    void apply() {
      final CompositeChaosTcpResetStorm ann =
          fixture(TcpResetStormFixture.class, CompositeChaosTcpResetStorm.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final CompositeConnectionChaos composite = mock(CompositeConnectionChaos.class);
      advMock(composite, c, h);

      try (final MockedStatic<CompositeConnectionChaos> mocked =
          mockStatic(CompositeConnectionChaos.class)) {
        mocked.when(CompositeConnectionChaos::standard).thenReturn(composite);
        final List<Object> handles = new TcpResetStormComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        org.mockito.Mockito.verify(composite.advanced())
            .apply(eq(c), argCorruptRule(0.3));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new TcpResetStormComposer().removeAll(container(), List.of());
    }

    @CompositeChaosTcpResetStorm
    static class TcpResetStormFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // PortAlreadyInUse
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosPortAlreadyInUse")
  class PortAlreadyInUseTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosPortAlreadyInUse.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("PortAlreadyInUseComposer");
    }

    @Test
    @DisplayName("endpoint() defaults to \"*\"")
    void annotation_has_wildcard_endpoint_by_default() throws Exception {
      var ann = PortAlreadyInUseFixture.class.getAnnotation(CompositeChaosPortAlreadyInUse.class);
      assertThat(ann.endpoint()).isEqualTo("*");
    }

    @Test
    @DisplayName("describe() returns non-empty list mentioning EADDRINUSE")
    void describe() {
      final CompositeChaosPortAlreadyInUse ann =
          fixture(PortAlreadyInUseFixture.class, CompositeChaosPortAlreadyInUse.class);
      final List<String> lines = new PortAlreadyInUseComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines)).containsIgnoringCase("EADDRINUSE");
    }

    @Test
    @DisplayName("apply() builds EADDRINUSE errno rule on BIND with wildcard endpoint")
    void apply() {
      final CompositeChaosPortAlreadyInUse ann =
          fixture(PortAlreadyInUseFixture.class, CompositeChaosPortAlreadyInUse.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final CompositeConnectionChaos composite = mock(CompositeConnectionChaos.class);
      advMock(composite, c, h);

      try (final MockedStatic<CompositeConnectionChaos> mocked =
          mockStatic(CompositeConnectionChaos.class)) {
        mocked.when(CompositeConnectionChaos::standard).thenReturn(composite);
        final List<Object> handles = new PortAlreadyInUseComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        org.mockito.Mockito.verify(composite.advanced())
            .apply(eq(c), argErrnoRule(NetOperation.BIND, Errno.EADDRINUSE));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new PortAlreadyInUseComposer().removeAll(container(), List.of());
    }

    @CompositeChaosPortAlreadyInUse
    static class PortAlreadyInUseFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SocketEphemeralExhaustion
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosSocketEphemeralExhaustion")
  class SocketEphemeralExhaustionTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosSocketEphemeralExhaustion.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("SocketEphemeralExhaustionComposer");
    }

    @Test
    @DisplayName("endpoint() defaults to \"*\"")
    void annotation_has_wildcard_endpoint_by_default() throws Exception {
      var ann = SocketEphemeralExhaustionFixture.class.getAnnotation(CompositeChaosSocketEphemeralExhaustion.class);
      assertThat(ann.endpoint()).isEqualTo("*");
    }

    @Test
    @DisplayName("describe() returns non-empty list mentioning EADDRNOTAVAIL")
    void describe() {
      final CompositeChaosSocketEphemeralExhaustion ann =
          fixture(SocketEphemeralExhaustionFixture.class, CompositeChaosSocketEphemeralExhaustion.class);
      final List<String> lines = new SocketEphemeralExhaustionComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines)).containsIgnoringCase("EADDRNOTAVAIL");
    }

    @Test
    @DisplayName("apply() builds EADDRNOTAVAIL errno rule on BIND with wildcard endpoint")
    void apply() {
      final CompositeChaosSocketEphemeralExhaustion ann =
          fixture(SocketEphemeralExhaustionFixture.class, CompositeChaosSocketEphemeralExhaustion.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final CompositeConnectionChaos composite = mock(CompositeConnectionChaos.class);
      advMock(composite, c, h);

      try (final MockedStatic<CompositeConnectionChaos> mocked =
          mockStatic(CompositeConnectionChaos.class)) {
        mocked.when(CompositeConnectionChaos::standard).thenReturn(composite);
        final List<Object> handles = new SocketEphemeralExhaustionComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        org.mockito.Mockito.verify(composite.advanced())
            .apply(eq(c), argErrnoRule(NetOperation.BIND, Errno.EADDRNOTAVAIL));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new SocketEphemeralExhaustionComposer().removeAll(container(), List.of());
    }

    @CompositeChaosSocketEphemeralExhaustion
    static class SocketEphemeralExhaustionFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // AcceptStorm
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosAcceptStorm")
  class AcceptStormTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN + default toxicity 0.8")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosAcceptStorm.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("AcceptStormComposer");
      final CompositeChaosAcceptStorm ann =
          fixture(AcceptStormFixture.class, CompositeChaosAcceptStorm.class);
      assertThat(ann.toxicity()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("endpoint() defaults to \"*\"")
    void annotation_has_wildcard_endpoint_by_default() throws Exception {
      var ann = AcceptStormFixture.class.getAnnotation(CompositeChaosAcceptStorm.class);
      assertThat(ann.endpoint()).isEqualTo("*");
    }

    @Test
    @DisplayName("describe() returns non-empty list mentioning EMFILE")
    void describe() {
      final CompositeChaosAcceptStorm ann =
          fixture(AcceptStormFixture.class, CompositeChaosAcceptStorm.class);
      final List<String> lines = new AcceptStormComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines)).containsIgnoringCase("EMFILE");
    }

    @Test
    @DisplayName("apply() builds EMFILE errno rule on ACCEPT with wildcard endpoint")
    void apply() {
      final CompositeChaosAcceptStorm ann =
          fixture(AcceptStormFixture.class, CompositeChaosAcceptStorm.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final CompositeConnectionChaos composite = mock(CompositeConnectionChaos.class);
      advMock(composite, c, h);

      try (final MockedStatic<CompositeConnectionChaos> mocked =
          mockStatic(CompositeConnectionChaos.class)) {
        mocked.when(CompositeConnectionChaos::standard).thenReturn(composite);
        final List<Object> handles = new AcceptStormComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        org.mockito.Mockito.verify(composite.advanced())
            .apply(eq(c), argErrnoRule(NetOperation.ACCEPT, Errno.EMFILE));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new AcceptStormComposer().removeAll(container(), List.of());
    }

    @CompositeChaosAcceptStorm
    static class AcceptStormFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SendBufferStarvation
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosSendBufferStarvation")
  class SendBufferStarvationTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN + default toxicity 0.5")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosSendBufferStarvation.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("SendBufferStarvationComposer");
      final CompositeChaosSendBufferStarvation ann =
          fixture(SendBufferStarvationFixture.class, CompositeChaosSendBufferStarvation.class);
      assertThat(ann.toxicity()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("endpoint() defaults to \"*\"")
    void annotation_has_wildcard_endpoint_by_default() throws Exception {
      var ann = SendBufferStarvationFixture.class.getAnnotation(CompositeChaosSendBufferStarvation.class);
      assertThat(ann.endpoint()).isEqualTo("*");
    }

    @Test
    @DisplayName("describe() returns non-empty list mentioning ENOBUFS")
    void describe() {
      final CompositeChaosSendBufferStarvation ann =
          fixture(SendBufferStarvationFixture.class, CompositeChaosSendBufferStarvation.class);
      final List<String> lines = new SendBufferStarvationComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines)).containsIgnoringCase("ENOBUFS");
    }

    @Test
    @DisplayName("apply() builds ENOBUFS errno rule on SEND with wildcard endpoint")
    void apply() {
      final CompositeChaosSendBufferStarvation ann =
          fixture(SendBufferStarvationFixture.class, CompositeChaosSendBufferStarvation.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final CompositeConnectionChaos composite = mock(CompositeConnectionChaos.class);
      advMock(composite, c, h);

      try (final MockedStatic<CompositeConnectionChaos> mocked =
          mockStatic(CompositeConnectionChaos.class)) {
        mocked.when(CompositeConnectionChaos::standard).thenReturn(composite);
        final List<Object> handles = new SendBufferStarvationComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        org.mockito.Mockito.verify(composite.advanced())
            .apply(eq(c), argErrnoRule(NetOperation.SEND, Errno.ENOBUFS));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new SendBufferStarvationComposer().removeAll(container(), List.of());
    }

    @CompositeChaosSendBufferStarvation
    static class SendBufferStarvationFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // PollTimeout
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosPollTimeout")
  class PollTimeoutTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN + default timeoutMs 5000")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosPollTimeout.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("PollTimeoutComposer");
      final CompositeChaosPollTimeout ann =
          fixture(PollTimeoutFixture.class, CompositeChaosPollTimeout.class);
      assertThat(ann.timeoutMs()).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("endpoint() defaults to \"*\"")
    void annotation_has_wildcard_endpoint_by_default() throws Exception {
      var ann = PollTimeoutFixture.class.getAnnotation(CompositeChaosPollTimeout.class);
      assertThat(ann.endpoint()).isEqualTo("*");
    }

    @Test
    @DisplayName("describe() returns non-empty list mentioning the timeout duration")
    void describe() {
      final CompositeChaosPollTimeout ann =
          fixture(PollTimeoutFixture.class, CompositeChaosPollTimeout.class);
      final List<String> lines = new PollTimeoutComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines)).contains("5000");
    }

    @Test
    @DisplayName("apply() builds TIMEOUT rule on POLL with wildcard endpoint and 5000ms duration")
    void apply() {
      final CompositeChaosPollTimeout ann =
          fixture(PollTimeoutFixture.class, CompositeChaosPollTimeout.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final CompositeConnectionChaos composite = mock(CompositeConnectionChaos.class);
      advMock(composite, c, h);

      try (final MockedStatic<CompositeConnectionChaos> mocked =
          mockStatic(CompositeConnectionChaos.class)) {
        mocked.when(CompositeConnectionChaos::standard).thenReturn(composite);
        final List<Object> handles = new PollTimeoutComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        org.mockito.Mockito.verify(composite.advanced())
            .apply(eq(c), argTimeoutRule(Duration.ofMillis(5_000)));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new PollTimeoutComposer().removeAll(container(), List.of());
    }

    @CompositeChaosPollTimeout
    static class PollTimeoutFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // HalfOpenConnection
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosHalfOpenConnection")
  class HalfOpenConnectionTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN + default toxicity 0.3")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosHalfOpenConnection.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("HalfOpenConnectionComposer");
      final CompositeChaosHalfOpenConnection ann =
          fixture(HalfOpenConnectionFixture.class, CompositeChaosHalfOpenConnection.class);
      assertThat(ann.toxicity()).isEqualTo(0.3);
    }

    @Test
    @DisplayName("endpoint() defaults to \"*\"")
    void annotation_has_wildcard_endpoint_by_default() throws Exception {
      var ann = HalfOpenConnectionFixture.class.getAnnotation(CompositeChaosHalfOpenConnection.class);
      assertThat(ann.endpoint()).isEqualTo("*");
    }

    @Test
    @DisplayName("describe() returns non-empty list mentioning ECONNRESET")
    void describe() {
      final CompositeChaosHalfOpenConnection ann =
          fixture(HalfOpenConnectionFixture.class, CompositeChaosHalfOpenConnection.class);
      final List<String> lines = new HalfOpenConnectionComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines)).containsIgnoringCase("ECONNRESET");
    }

    @Test
    @DisplayName("apply() builds ECONNRESET errno rule on RECV with wildcard endpoint")
    void apply() {
      final CompositeChaosHalfOpenConnection ann =
          fixture(HalfOpenConnectionFixture.class, CompositeChaosHalfOpenConnection.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final CompositeConnectionChaos composite = mock(CompositeConnectionChaos.class);
      advMock(composite, c, h);

      try (final MockedStatic<CompositeConnectionChaos> mocked =
          mockStatic(CompositeConnectionChaos.class)) {
        mocked.when(CompositeConnectionChaos::standard).thenReturn(composite);
        final List<Object> handles = new HalfOpenConnectionComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        org.mockito.Mockito.verify(composite.advanced())
            .apply(eq(c), argErrnoRule(NetOperation.RECV, Errno.ECONNRESET));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new HalfOpenConnectionComposer().removeAll(container(), List.of());
    }

    @CompositeChaosHalfOpenConnection
    static class HalfOpenConnectionFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SlowDownstream
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosSlowDownstream")
  class SlowDownstreamTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN + defaults latencyMs=500, sendFailToxicity=0.05")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosSlowDownstream.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("SlowDownstreamComposer");
      final CompositeChaosSlowDownstream ann =
          fixture(SlowDownstreamFixture.class, CompositeChaosSlowDownstream.class);
      assertThat(ann.latencyMs()).isEqualTo(500L);
      assertThat(ann.sendFailToxicity()).isEqualTo(0.05);
    }

    @Test
    @DisplayName("endpoint() defaults to \"*\"")
    void annotation_has_wildcard_endpoint_by_default() throws Exception {
      var ann = SlowDownstreamFixture.class.getAnnotation(CompositeChaosSlowDownstream.class);
      assertThat(ann.endpoint()).isEqualTo("*");
    }

    @Test
    @DisplayName("describe() returns non-empty list mentioning latency value")
    void describe() {
      final CompositeChaosSlowDownstream ann =
          fixture(SlowDownstreamFixture.class, CompositeChaosSlowDownstream.class);
      final List<String> lines = new SlowDownstreamComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines)).contains("500");
    }

    @Test
    @DisplayName("apply() builds LATENCY rule on CONNECT and EPIPE errno rule on SEND — two handles")
    void apply() {
      final CompositeChaosSlowDownstream ann =
          fixture(SlowDownstreamFixture.class, CompositeChaosSlowDownstream.class);
      final GenericContainer<?> c = container();
      final RuleHandle h1 = handle();
      final RuleHandle h2 = handle();
      final AdvancedConnectionChaos adv = mock(AdvancedConnectionChaos.class);
      final CompositeConnectionChaos composite = mock(CompositeConnectionChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.apply(eq(c), argLatencyRule(NetOperation.CONNECT, Duration.ofMillis(500)))).thenReturn(h1);
      when(adv.apply(eq(c), argErrnoRule(NetOperation.SEND, Errno.EPIPE))).thenReturn(h2);

      try (final MockedStatic<CompositeConnectionChaos> mocked =
          mockStatic(CompositeConnectionChaos.class)) {
        mocked.when(CompositeConnectionChaos::standard).thenReturn(composite);
        final List<Object> handles = new SlowDownstreamComposer().apply(c, ann);
        assertThat(handles).containsExactly(h1, h2);
        org.mockito.Mockito.verify(adv)
            .apply(eq(c), argLatencyRule(NetOperation.CONNECT, Duration.ofMillis(500)));
        org.mockito.Mockito.verify(adv)
            .apply(eq(c), argErrnoRule(NetOperation.SEND, Errno.EPIPE));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new SlowDownstreamComposer().removeAll(container(), List.of());
    }

    @CompositeChaosSlowDownstream
    static class SlowDownstreamFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // L2Composer contract — all 12 composers implement the interface
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("all 12 composers implement L2Composer")
  void allComposersImplementInterface() {
    assertThat(new ConnectionRefusedComposer()).isInstanceOf(L2Composer.class);
    assertThat(new ThunderingHerdComposer()).isInstanceOf(L2Composer.class);
    assertThat(new UnreachableHostComposer()).isInstanceOf(L2Composer.class);
    assertThat(new UnreachableNetworkComposer()).isInstanceOf(L2Composer.class);
    assertThat(new TcpResetStormComposer()).isInstanceOf(L2Composer.class);
    assertThat(new PortAlreadyInUseComposer()).isInstanceOf(L2Composer.class);
    assertThat(new SocketEphemeralExhaustionComposer()).isInstanceOf(L2Composer.class);
    assertThat(new AcceptStormComposer()).isInstanceOf(L2Composer.class);
    assertThat(new SendBufferStarvationComposer()).isInstanceOf(L2Composer.class);
    assertThat(new PollTimeoutComposer()).isInstanceOf(L2Composer.class);
    assertThat(new HalfOpenConnectionComposer()).isInstanceOf(L2Composer.class);
    assertThat(new SlowDownstreamComposer()).isInstanceOf(L2Composer.class);
  }

  @Test
  @DisplayName("all 12 annotations are repeatable (carry @Repeatable)")
  void allAnnotationsRepeatable() {
    assertThat(CompositeChaosConnectionRefused.class
        .getAnnotation(java.lang.annotation.Repeatable.class)).isNotNull();
    assertThat(CompositeChaosThunderingHerd.class
        .getAnnotation(java.lang.annotation.Repeatable.class)).isNotNull();
    assertThat(CompositeChaosUnreachableHost.class
        .getAnnotation(java.lang.annotation.Repeatable.class)).isNotNull();
    assertThat(CompositeChaosUnreachableNetwork.class
        .getAnnotation(java.lang.annotation.Repeatable.class)).isNotNull();
    assertThat(CompositeChaosSlowDownstream.class
        .getAnnotation(java.lang.annotation.Repeatable.class)).isNotNull();
    assertThat(CompositeChaosTcpResetStorm.class
        .getAnnotation(java.lang.annotation.Repeatable.class)).isNotNull();
    assertThat(CompositeChaosPortAlreadyInUse.class
        .getAnnotation(java.lang.annotation.Repeatable.class)).isNotNull();
    assertThat(CompositeChaosSocketEphemeralExhaustion.class
        .getAnnotation(java.lang.annotation.Repeatable.class)).isNotNull();
    assertThat(CompositeChaosAcceptStorm.class
        .getAnnotation(java.lang.annotation.Repeatable.class)).isNotNull();
    assertThat(CompositeChaosSendBufferStarvation.class
        .getAnnotation(java.lang.annotation.Repeatable.class)).isNotNull();
    assertThat(CompositeChaosPollTimeout.class
        .getAnnotation(java.lang.annotation.Repeatable.class)).isNotNull();
    assertThat(CompositeChaosHalfOpenConnection.class
        .getAnnotation(java.lang.annotation.Repeatable.class)).isNotNull();
  }
}
