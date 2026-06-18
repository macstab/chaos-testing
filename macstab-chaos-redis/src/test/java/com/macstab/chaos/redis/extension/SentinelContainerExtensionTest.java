/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.api.SentinelRedis;

/** Unit tests for {@link SentinelContainerExtension} — zero-Docker, Mockito-only. */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("SentinelContainerExtension")
class SentinelContainerExtensionTest {

  // ==================== Reflection Helpers ====================

  @SuppressWarnings("unchecked")
  private ThreadLocal<ExtensionContext> getThreadLocal() throws Exception {
    final Field f = SentinelContainerExtension.class.getDeclaredField("CURRENT_CONTEXT");
    f.setAccessible(true);
    return (ThreadLocal<ExtensionContext>) f.get(null);
  }

  private ExtensionContext.Namespace getNamespace() throws Exception {
    final Field nsField = SentinelContainerExtension.class.getDeclaredField("NAMESPACE");
    nsField.setAccessible(true);
    return (ExtensionContext.Namespace) nsField.get(null);
  }

  private String getClusterMapKey() throws Exception {
    final Field f = SentinelContainerExtension.class.getDeclaredField("CLUSTER_MAP_KEY");
    f.setAccessible(true);
    return (String) f.get(null);
  }

  private String getAnnotationsKey() throws Exception {
    final Field f = SentinelContainerExtension.class.getDeclaredField("ANNOTATIONS_KEY");
    f.setAccessible(true);
    return (String) f.get(null);
  }

  /** Returns a mocked ExtensionContext backed by a store that returns the given clusters. */
  private ExtensionContext contextWithClusters(final Map<String, SentinelCluster> clusters)
      throws Exception {
    final ExtensionContext ctx = mock(ExtensionContext.class);
    final ExtensionContext.Store store = mock(ExtensionContext.Store.class);
    final ExtensionContext.Namespace ns = getNamespace();
    final String clusterMapKey = getClusterMapKey();

    when(ctx.getStore(eq(ns))).thenReturn(store);

    // Build CloseableClusterMap via reflection
    final Class<?>[] innerClasses = SentinelContainerExtension.class.getDeclaredClasses();
    Class<?> closeableClusterMapClass = null;
    for (final Class<?> inner : innerClasses) {
      if (inner.getSimpleName().equals("CloseableClusterMap")) {
        closeableClusterMapClass = inner;
        break;
      }
    }
    if (closeableClusterMapClass != null && clusters != null) {
      final java.lang.reflect.Constructor<?> ctor =
          closeableClusterMapClass.getDeclaredConstructor(Map.class);
      ctor.setAccessible(true);
      final Object wrapper = ctor.newInstance(clusters);
      doReturn(wrapper).when(store).get(eq(clusterMapKey), eq(closeableClusterMapClass));
    } else {
      doReturn(null).when(store).get(any(), any(Class.class));
    }
    return ctx;
  }

  private ExtensionContext contextWithClustersAndAnnotations(
      final Map<String, SentinelCluster> clusters, final RedisSentinel[] annotations)
      throws Exception {
    final ExtensionContext ctx = contextWithClusters(clusters);
    final ExtensionContext.Store store = ctx.getStore(getNamespace());
    final String annotationsKey = getAnnotationsKey();
    when(store.get(eq(annotationsKey), eq(RedisSentinel[].class))).thenReturn(annotations);
    return ctx;
  }

  /**
   * Creates a minimal SentinelCluster using mock containers that respond to getMappedPort /
   * getHost.
   */
  private SentinelCluster buildCluster() {
    final Network network = mock(Network.class);
    final GenericContainer<?> master = mock(GenericContainer.class);
    // lenient: only invoked when toSentinelRedis() / getMasterHost() / getMasterPort() is called
    lenient().when(master.getHost()).thenReturn("localhost");
    lenient().when(master.getMappedPort(6379)).thenReturn(6380);
    return new SentinelCluster(network, master, List.of(), List.of(), "mymaster");
  }

  @BeforeEach
  void clearContext() throws Exception {
    getThreadLocal().remove();
  }

  @AfterEach
  void cleanupContext() throws Exception {
    getThreadLocal().remove();
  }

  // ==================== getCluster() ====================

  @Nested
  @DisplayName("getCluster(String)")
  class GetCluster {

    @Test
    @DisplayName("CURRENT_CONTEXT null → ISE")
    void currentContextNull() {
      // ARRANGE — ThreadLocal not set

      // ACT + ASSERT
      assertThatThrownBy(() -> SentinelContainerExtension.getCluster("default"))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("id=default, single cluster → returns it")
    void defaultWithSingleCluster() throws Exception {
      // ARRANGE
      final SentinelCluster cluster = buildCluster();
      final Map<String, SentinelCluster> clusters = Map.of("mycluster", cluster);
      final ExtensionContext ctx = contextWithClusters(clusters);
      getThreadLocal().set(ctx);

      // ACT
      final SentinelCluster result = SentinelContainerExtension.getCluster("default");

      // ASSERT
      assertThat(result).isSameAs(cluster);
    }

    @Test
    @DisplayName("id matches → returns it")
    void idMatches() throws Exception {
      // ARRANGE
      final SentinelCluster cluster = buildCluster();
      final Map<String, SentinelCluster> clusters = Map.of("primary", cluster);
      final ExtensionContext ctx = contextWithClusters(clusters);
      getThreadLocal().set(ctx);

      // ACT
      final SentinelCluster result = SentinelContainerExtension.getCluster("primary");

      // ASSERT
      assertThat(result).isSameAs(cluster);
    }

    @Test
    @DisplayName("id=default, multiple clusters, no default key → IAE mentioning Multiple")
    void defaultWithMultipleClusters() throws Exception {
      // ARRANGE
      final Map<String, SentinelCluster> clusters =
          Map.of(
              "primary", buildCluster(),
              "secondary", buildCluster());
      final ExtensionContext ctx = contextWithClusters(clusters);
      getThreadLocal().set(ctx);

      // ACT + ASSERT
      assertThatThrownBy(() -> SentinelContainerExtension.getCluster("default"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Multiple");
    }

    @Test
    @DisplayName("id not found, non-default → IAE mentioning No Sentinel cluster")
    void idNotFoundNonDefault() throws Exception {
      // ARRANGE
      final Map<String, SentinelCluster> clusters = Map.of("primary", buildCluster());
      final ExtensionContext ctx = contextWithClusters(clusters);
      getThreadLocal().set(ctx);

      // ACT + ASSERT
      assertThatThrownBy(() -> SentinelContainerExtension.getCluster("missing"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("No Sentinel cluster");
    }
  }

  // ==================== getAllClusters() ====================

  @Nested
  @DisplayName("getAllClusters()")
  class GetAllClusters {

    @Test
    @DisplayName("CURRENT_CONTEXT null → ISE")
    void currentContextNull() {
      // ARRANGE — ThreadLocal not set

      // ACT + ASSERT
      assertThatThrownBy(() -> SentinelContainerExtension.getAllClusters())
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("annotations null → returns empty list")
    void annotationsNull() throws Exception {
      // ARRANGE
      final ExtensionContext ctx = mock(ExtensionContext.class);
      final ExtensionContext.Store store = mock(ExtensionContext.Store.class);
      final ExtensionContext.Namespace ns = getNamespace();
      final String annotationsKey = getAnnotationsKey();
      when(ctx.getStore(eq(ns))).thenReturn(store);
      when(store.get(eq(annotationsKey), eq(RedisSentinel[].class))).thenReturn(null);
      getThreadLocal().set(ctx);

      // ACT
      final List<SentinelCluster> result = SentinelContainerExtension.getAllClusters();

      // ASSERT
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("clusters returned in annotation declaration order")
    void clustersInAnnotationOrder() throws Exception {
      // ARRANGE
      final SentinelCluster cluster1 = buildCluster();
      final SentinelCluster cluster2 = buildCluster();
      final Map<String, SentinelCluster> clusters = new LinkedHashMap<>();
      clusters.put("first", cluster1);
      clusters.put("second", cluster2);

      final RedisSentinel ann1 = mock(RedisSentinel.class);
      final RedisSentinel ann2 = mock(RedisSentinel.class);
      when(ann1.id()).thenReturn("first");
      when(ann2.id()).thenReturn("second");
      final RedisSentinel[] annotations = {ann1, ann2};

      final ExtensionContext ctx = contextWithClustersAndAnnotations(clusters, annotations);
      getThreadLocal().set(ctx);

      // ACT
      final List<SentinelCluster> result = SentinelContainerExtension.getAllClusters();

      // ASSERT
      assertThat(result).containsExactly(cluster1, cluster2);
    }
  }

  // ==================== getClustersMap() with null wrapper ====================

  @Nested
  @DisplayName("getClustersMap() — null wrapper returns emptyMap")
  class GetClustersMapNullWrapper {

    @Test
    @DisplayName("null wrapper in store → getCluster on single-cluster fails with IAE")
    void nullWrapperReturnsEmptyMap() throws Exception {
      // ARRANGE — no cluster map stored
      final ExtensionContext ctx = contextWithClusters(null);
      getThreadLocal().set(ctx);

      // ACT + ASSERT: empty map → "default" with size 0 → falls through to null check → IAE
      assertThatThrownBy(() -> SentinelContainerExtension.getCluster("anything"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  // ==================== supportsParameter() ====================

  @Nested
  @DisplayName("supportsParameter()")
  class SupportsParameter {

    private final SentinelContainerExtension extension = new SentinelContainerExtension();

    @Test
    @DisplayName("SentinelCluster.class → true")
    void sentinelCluster() {
      final ParameterContext paramCtx = mockParameterContext(SentinelCluster.class, null);
      assertThat(extension.supportsParameter(paramCtx, mock(ExtensionContext.class))).isTrue();
    }

    @Test
    @DisplayName("SentinelRedis.class → true")
    void sentinelRedis() {
      final ParameterContext paramCtx = mockParameterContext(SentinelRedis.class, null);
      assertThat(extension.supportsParameter(paramCtx, mock(ExtensionContext.class))).isTrue();
    }

    @Test
    @DisplayName("List<SentinelCluster> → true")
    void listSentinelCluster() {
      final ParameterizedType pt = mockParameterizedType(SentinelCluster.class);
      final ParameterContext paramCtx = mockParameterContext(List.class, pt);
      assertThat(extension.supportsParameter(paramCtx, mock(ExtensionContext.class))).isTrue();
    }

    @Test
    @DisplayName("List<SentinelRedis> → true")
    void listSentinelRedis() {
      final ParameterizedType pt = mockParameterizedType(SentinelRedis.class);
      final ParameterContext paramCtx = mockParameterContext(List.class, pt);
      assertThat(extension.supportsParameter(paramCtx, mock(ExtensionContext.class))).isTrue();
    }

    @Test
    @DisplayName("String.class → false")
    void stringType() {
      final ParameterContext paramCtx = mockParameterContext(String.class, null);
      assertThat(extension.supportsParameter(paramCtx, mock(ExtensionContext.class))).isFalse();
    }

    @Test
    @DisplayName("List.class with raw type (no ParameterizedType) → false")
    void listWithRawType() {
      final Parameter param = mock(Parameter.class);
      when(param.getType()).thenReturn((Class) List.class);
      when(param.getParameterizedType()).thenReturn(List.class);
      final ParameterContext paramCtx = mock(ParameterContext.class);
      when(paramCtx.getParameter()).thenReturn(param);
      assertThat(extension.supportsParameter(paramCtx, mock(ExtensionContext.class))).isFalse();
    }

    private ParameterContext mockParameterContext(final Class<?> type, final ParameterizedType pt) {
      final Parameter param = mock(Parameter.class);
      when(param.getType()).thenReturn((Class) type);
      if (pt != null) {
        when(param.getParameterizedType()).thenReturn(pt);
      } else {
        // lenient: only invoked when code enters the List branch
        lenient().when(param.getParameterizedType()).thenReturn(type);
      }
      final ParameterContext ctx = mock(ParameterContext.class);
      when(ctx.getParameter()).thenReturn(param);
      return ctx;
    }

    private ParameterizedType mockParameterizedType(final Class<?> typeArg) {
      final ParameterizedType pt = mock(ParameterizedType.class);
      when(pt.getActualTypeArguments()).thenReturn(new Type[] {typeArg});
      return pt;
    }
  }

  // ==================== resolveParameter() ====================

  @Nested
  @DisplayName("resolveParameter()")
  class ResolveParameter {

    private final SentinelContainerExtension extension = new SentinelContainerExtension();

    @Test
    @DisplayName("SentinelRedis type → returns SentinelRedis")
    void sentinelRedisType() throws Exception {
      // ARRANGE — use a mock SentinelCluster so toSentinelRedis() doesn't need real containers
      final SentinelCluster cluster = mock(SentinelCluster.class);
      final com.macstab.chaos.redis.api.Endpoint sentinel =
          new com.macstab.chaos.redis.api.Endpoint("localhost", 26379);
      final SentinelRedis sentinelRedis =
          new SentinelRedis("localhost", 6380, "mymaster", List.of(sentinel), List.of());
      lenient().when(cluster.toSentinelRedis()).thenReturn(sentinelRedis);

      final Map<String, SentinelCluster> clusters = Map.of("single", cluster);
      final ExtensionContext ctx = contextWithClusters(clusters);
      getThreadLocal().set(ctx);

      final ParameterContext paramCtx = mockParameterContext(SentinelRedis.class, null);

      // ACT
      final Object result = extension.resolveParameter(paramCtx, mock(ExtensionContext.class));

      // ASSERT
      assertThat(result).isInstanceOf(SentinelRedis.class);
    }

    @Test
    @DisplayName("List<SentinelCluster> → returns list of clusters")
    void listSentinelClusterType() throws Exception {
      // ARRANGE
      final SentinelCluster c1 = buildCluster();
      final SentinelCluster c2 = buildCluster();
      final Map<String, SentinelCluster> clusters = new LinkedHashMap<>();
      clusters.put("first", c1);
      clusters.put("second", c2);

      final RedisSentinel ann1 = mock(RedisSentinel.class);
      final RedisSentinel ann2 = mock(RedisSentinel.class);
      when(ann1.id()).thenReturn("first");
      when(ann2.id()).thenReturn("second");
      final ExtensionContext ctx =
          contextWithClustersAndAnnotations(clusters, new RedisSentinel[] {ann1, ann2});
      getThreadLocal().set(ctx);

      final ParameterizedType pt = mock(ParameterizedType.class);
      when(pt.getActualTypeArguments()).thenReturn(new Type[] {SentinelCluster.class});
      final ParameterContext paramCtx = mockParameterContext(List.class, pt);

      // ACT
      @SuppressWarnings("unchecked")
      final List<SentinelCluster> result =
          (List<SentinelCluster>)
              extension.resolveParameter(paramCtx, mock(ExtensionContext.class));

      // ASSERT
      assertThat(result).containsExactly(c1, c2);
    }

    @Test
    @DisplayName("SentinelCluster (default) → returns cluster")
    void defaultClusterType() throws Exception {
      // ARRANGE
      final SentinelCluster cluster = buildCluster();
      final Map<String, SentinelCluster> clusters = Map.of("only", cluster);
      final ExtensionContext ctx = contextWithClusters(clusters);
      getThreadLocal().set(ctx);

      final ParameterContext paramCtx = mockParameterContext(SentinelCluster.class, null);

      // ACT
      final Object result = extension.resolveParameter(paramCtx, mock(ExtensionContext.class));

      // ASSERT
      assertThat(result).isSameAs(cluster);
    }

    private ParameterContext mockParameterContext(final Class<?> type, final ParameterizedType pt) {
      final Parameter param = mock(Parameter.class);
      when(param.getType()).thenReturn((Class) type);
      if (pt != null) {
        when(param.getParameterizedType()).thenReturn(pt);
      } else {
        lenient().when(param.getParameterizedType()).thenReturn(type);
      }
      final ParameterContext ctx = mock(ParameterContext.class);
      when(ctx.getParameter()).thenReturn(param);
      return ctx;
    }
  }
}
