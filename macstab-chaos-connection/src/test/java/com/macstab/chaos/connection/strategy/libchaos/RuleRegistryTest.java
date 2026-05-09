/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.strategy.libchaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.api.RuleHandle;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;

@DisplayName("RuleRegistry")
class RuleRegistryTest {

  private RuleRegistry registry;
  private GenericContainer<?> c1;
  private GenericContainer<?> c2;

  private static final NetRule RULE =
      NetRule.errno(Endpoint.tcp4("db", 5432), NetOperation.CONNECT, Errno.ECONNREFUSED, 1.0);

  @BeforeEach
  void setUp() {
    registry = new RuleRegistry();
    c1 = mock(GenericContainer.class);
    c2 = mock(GenericContainer.class);
  }

  @Nested
  @DisplayName("register")
  class Register {

    @Test
    @DisplayName("entry appears in snapshot for the same container")
    void registered() {
      final RuleHandle h = new RuleHandle("r1");
      registry.register(c1, new RuleRegistry.Entry(h, RULE, null));
      assertThat(registry.snapshot(c1))
          .extracting(RuleRegistry.Entry::handle)
          .containsExactly(h);
    }

    @Test
    @DisplayName("entries on different containers do not bleed")
    void containersIsolated() {
      registry.register(c1, new RuleRegistry.Entry(new RuleHandle("r1"), RULE, null));
      registry.register(c2, new RuleRegistry.Entry(new RuleHandle("r2"), RULE, null));
      assertThat(registry.snapshot(c1)).hasSize(1);
      assertThat(registry.snapshot(c2)).hasSize(1);
    }

    @Test
    @DisplayName("null container is rejected")
    void nullContainer() {
      assertThatThrownBy(
              () -> registry.register(null, new RuleRegistry.Entry(new RuleHandle("r1"), RULE, null)))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("remove(handle)")
  class Remove {

    @Test
    @DisplayName("removes and returns the entry")
    void removes() {
      final RuleHandle h = new RuleHandle("r1");
      registry.register(c1, new RuleRegistry.Entry(h, RULE, "latency"));
      assertThat(registry.remove(c1, h))
          .hasValueSatisfying(e -> assertThat(e.toxicName()).isEqualTo("latency"));
      assertThat(registry.snapshot(c1)).isEmpty();
    }

    @Test
    @DisplayName("unknown handle returns empty")
    void unknown() {
      assertThat(registry.remove(c1, new RuleHandle("unknown"))).isEmpty();
    }
  }

  @Nested
  @DisplayName("removeAll(container)")
  class RemoveAll {

    @Test
    @DisplayName("returns and clears every entry for the container")
    void wipesContainer() {
      registry.register(c1, new RuleRegistry.Entry(new RuleHandle("r1"), RULE, null));
      registry.register(c1, new RuleRegistry.Entry(new RuleHandle("r2"), RULE, "latency"));
      assertThat(registry.removeAll(c1)).hasSize(2);
      assertThat(registry.snapshot(c1)).isEmpty();
    }

    @Test
    @DisplayName("does not affect other containers")
    void isolation() {
      registry.register(c1, new RuleRegistry.Entry(new RuleHandle("r1"), RULE, null));
      registry.register(c2, new RuleRegistry.Entry(new RuleHandle("r2"), RULE, null));
      registry.removeAll(c1);
      assertThat(registry.snapshot(c2)).hasSize(1);
    }
  }

  @Nested
  @DisplayName("removeByToxicName")
  class RemoveByToxicName {

    @Test
    @DisplayName("removes only entries with the matching tag")
    void filtersByTag() {
      registry.register(c1, new RuleRegistry.Entry(new RuleHandle("r1"), RULE, "latency"));
      registry.register(c1, new RuleRegistry.Entry(new RuleHandle("r2"), RULE, "down"));
      registry.register(c1, new RuleRegistry.Entry(new RuleHandle("r3"), RULE, null));
      assertThat(registry.removeByToxicName(c1, "latency")).hasSize(1);
      assertThat(registry.snapshot(c1))
          .extracting(RuleRegistry.Entry::handle)
          .extracting(RuleHandle::owner)
          .containsExactlyInAnyOrder("r2", "r3");
    }
  }
}
