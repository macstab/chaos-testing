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

import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.redis.api.StandaloneRedis;
import com.macstab.chaos.redis.extension.RedisContainerExtension.RedisConnectionInfo;
import com.macstab.chaos.redis.extension.RedisContainerExtension.Store;

/** Unit tests for {@link RedisContainerExtension} — zero-Docker, Mockito-only. */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisContainerExtension")
class RedisContainerExtensionTest {

  // ==================== Reflection Helpers ====================

  @SuppressWarnings("unchecked")
  private ThreadLocal<ExtensionContext> getThreadLocal() throws Exception {
    final Field f = RedisContainerExtension.class.getDeclaredField("CURRENT_CONTEXT");
    f.setAccessible(true);
    return (ThreadLocal<ExtensionContext>) f.get(null);
  }

  private ExtensionContext.Namespace getNamespace() throws Exception {
    final Field nsField = RedisContainerExtension.class.getDeclaredField("NAMESPACE");
    nsField.setAccessible(true);
    return (ExtensionContext.Namespace) nsField.get(null);
  }

  private String getKey(final String fieldName) throws Exception {
    final Field f = RedisContainerExtension.class.getDeclaredField(fieldName);
    f.setAccessible(true);
    return (String) f.get(null);
  }

  private ExtensionContext contextWithStore(
      final Map<String, RedisConnectionInfo> instances, final RedisStandalone[] annotations)
      throws Exception {
    final ExtensionContext ctx = mock(ExtensionContext.class);
    final ExtensionContext.Store store = mock(ExtensionContext.Store.class);
    final ExtensionContext.Namespace ns = getNamespace();
    final String containerKey = getKey("CONTAINER_MAP_KEY");
    final String annotationsKey = getKey("ANNOTATIONS_KEY");

    when(ctx.getStore(eq(ns))).thenReturn(store);
    when(store.get(eq(containerKey), eq(Map.class))).thenReturn(instances);
    if (annotations != null) {
      when(store.get(eq(annotationsKey), eq(RedisStandalone[].class))).thenReturn(annotations);
    }
    return ctx;
  }

  @BeforeEach
  void clearContext() throws Exception {
    getThreadLocal().remove();
  }

  @AfterEach
  void cleanupContext() throws Exception {
    getThreadLocal().remove();
  }

  // ==================== getContainer() ====================

  @Nested
  @DisplayName("getContainer(String)")
  class GetContainer {

    @Test
    @DisplayName("id=default, single instance → returns it")
    void defaultWithSingleInstance() throws Exception {
      // ARRANGE
      final RedisConnectionInfo info = new RedisConnectionInfo("localhost", 6379);
      final Map<String, RedisConnectionInfo> instances = Map.of("myredis", info);
      final ExtensionContext ctx = contextWithStore(instances, null);
      getThreadLocal().set(ctx);

      // ACT
      final RedisConnectionInfo result = RedisContainerExtension.getContainer("default");

      // ASSERT
      assertThat(result).isSameAs(info);
    }

    @Test
    @DisplayName("id matches key → returns it")
    void idMatchesKey() throws Exception {
      // ARRANGE
      final RedisConnectionInfo info = new RedisConnectionInfo("host1", 6380);
      final Map<String, RedisConnectionInfo> instances = Map.of("primary", info);
      final ExtensionContext ctx = contextWithStore(instances, null);
      getThreadLocal().set(ctx);

      // ACT
      final RedisConnectionInfo result = RedisContainerExtension.getContainer("primary");

      // ASSERT
      assertThat(result).isSameAs(info);
    }

    @Test
    @DisplayName("id=default, multiple instances, no default key → IAE mentioning Multiple")
    void defaultWithMultipleInstancesNoDefaultKey() throws Exception {
      // ARRANGE
      final Map<String, RedisConnectionInfo> instances =
          Map.of(
              "cache", new RedisConnectionInfo("h1", 6379),
              "session", new RedisConnectionInfo("h2", 6380));
      final ExtensionContext ctx = contextWithStore(instances, null);
      getThreadLocal().set(ctx);

      // ACT + ASSERT
      assertThatThrownBy(() -> RedisContainerExtension.getContainer("default"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Multiple");
    }

    @Test
    @DisplayName("id not found, non-default → IAE mentioning No Redis instance")
    void idNotFoundNonDefault() throws Exception {
      // ARRANGE
      final Map<String, RedisConnectionInfo> instances =
          Map.of("primary", new RedisConnectionInfo("h", 1234));
      final ExtensionContext ctx = contextWithStore(instances, null);
      getThreadLocal().set(ctx);

      // ACT + ASSERT
      assertThatThrownBy(() -> RedisContainerExtension.getContainer("missing"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("No Redis instance");
    }

    @Test
    @DisplayName("CURRENT_CONTEXT null → ISE")
    void currentContextNull() {
      // ARRANGE — ThreadLocal not set

      // ACT + ASSERT
      assertThatThrownBy(() -> RedisContainerExtension.getContainer("default"))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  // ==================== getContainerInstance() ====================

  @Nested
  @DisplayName("getContainerInstance(String)")
  class GetContainerInstance {

    @SuppressWarnings("unchecked")
    private ExtensionContext contextWithStoresMap(final Map<String, Store> storesMap)
        throws Exception {
      final ExtensionContext ctx = mock(ExtensionContext.class);
      final ExtensionContext.Store store = mock(ExtensionContext.Store.class);
      final ExtensionContext.Namespace ns = getNamespace();
      final String storesMapKey = getKey("STORES_MAP_KEY");

      when(ctx.getStore(eq(ns))).thenReturn(store);

      // Build a CloseableStoreMap by reflection
      final Class<?>[] innerClasses = RedisContainerExtension.class.getDeclaredClasses();
      Class<?> closeableStoreMapClass = null;
      for (final Class<?> inner : innerClasses) {
        if (inner.getSimpleName().equals("CloseableStoreMap")) {
          closeableStoreMapClass = inner;
          break;
        }
      }
      if (closeableStoreMapClass != null && storesMap != null) {
        final java.lang.reflect.Constructor<?> ctor =
            closeableStoreMapClass.getDeclaredConstructor(Map.class);
        ctor.setAccessible(true);
        final Object wrapper = ctor.newInstance(storesMap);
        doReturn(wrapper).when(store).get(eq(storesMapKey), eq(closeableStoreMapClass));
      } else {
        doReturn(null).when(store).get(any(), any(Class.class));
      }
      return ctx;
    }

    @Test
    @DisplayName("id=default, single store → returns container")
    void defaultWithSingleStore() throws Exception {
      // ARRANGE
      final GenericContainer<?> container = mock(GenericContainer.class);
      final RedisConnectionInfo info = new RedisConnectionInfo("localhost", 6379);
      final Store s = new Store(container, info);
      final ExtensionContext ctx = contextWithStoresMap(Map.of("myredis", s));
      getThreadLocal().set(ctx);

      // ACT
      final GenericContainer<?> result = RedisContainerExtension.getContainerInstance("default");

      // ASSERT
      assertThat(result).isSameAs(container);
    }

    @Test
    @DisplayName("id matches → returns container")
    void idMatches() throws Exception {
      // ARRANGE
      final GenericContainer<?> container = mock(GenericContainer.class);
      final RedisConnectionInfo info = new RedisConnectionInfo("localhost", 6379);
      final Store s = new Store(container, info);
      final ExtensionContext ctx = contextWithStoresMap(Map.of("primary", s));
      getThreadLocal().set(ctx);

      // ACT
      final GenericContainer<?> result = RedisContainerExtension.getContainerInstance("primary");

      // ASSERT
      assertThat(result).isSameAs(container);
    }

    @Test
    @DisplayName("id not found → IAE")
    void idNotFound() throws Exception {
      // ARRANGE
      final GenericContainer<?> container = mock(GenericContainer.class);
      final RedisConnectionInfo info = new RedisConnectionInfo("localhost", 6379);
      final Store s = new Store(container, info);
      final ExtensionContext ctx = contextWithStoresMap(Map.of("primary", s));
      getThreadLocal().set(ctx);

      // ACT + ASSERT
      assertThatThrownBy(() -> RedisContainerExtension.getContainerInstance("missing"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null wrapper in store → getStoresMap returns emptyMap → id=default IAE")
    void nullWrapperReturnsEmptyMap() throws Exception {
      // ARRANGE — no stores map set
      final ExtensionContext ctx = contextWithStoresMap(null);
      getThreadLocal().set(ctx);

      // ACT + ASSERT: default with empty map → not single, not found → falls to null check
      assertThatThrownBy(() -> RedisContainerExtension.getContainerInstance("missing"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  // ==================== getAllContainers() ====================

  @Nested
  @DisplayName("getAllContainers()")
  class GetAllContainers {

    @Test
    @DisplayName("annotations null in store → returns empty list")
    void annotationsNull() throws Exception {
      // ARRANGE
      final ExtensionContext ctx = mock(ExtensionContext.class);
      final ExtensionContext.Store store = mock(ExtensionContext.Store.class);
      final ExtensionContext.Namespace ns = getNamespace();
      final String annotationsKey = getKey("ANNOTATIONS_KEY");

      when(ctx.getStore(eq(ns))).thenReturn(store);
      when(store.get(eq(annotationsKey), eq(RedisStandalone[].class))).thenReturn(null);
      getThreadLocal().set(ctx);

      // ACT
      final List<RedisConnectionInfo> result = RedisContainerExtension.getAllContainers();

      // ASSERT
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("instances returned in annotation order")
    void instancesInAnnotationOrder() throws Exception {
      // ARRANGE
      final RedisConnectionInfo info1 = new RedisConnectionInfo("h1", 6379);
      final RedisConnectionInfo info2 = new RedisConnectionInfo("h2", 6380);
      final Map<String, RedisConnectionInfo> instances = new LinkedHashMap<>();
      instances.put("first", info1);
      instances.put("second", info2);

      final RedisStandalone ann1 = mockAnnotation("first");
      final RedisStandalone ann2 = mockAnnotation("second");
      final RedisStandalone[] annotations = {ann1, ann2};

      final ExtensionContext ctx = contextWithStore(instances, annotations);
      getThreadLocal().set(ctx);

      // ACT
      final List<RedisConnectionInfo> result = RedisContainerExtension.getAllContainers();

      // ASSERT
      assertThat(result).containsExactly(info1, info2);
    }

    private RedisStandalone mockAnnotation(final String id) {
      final RedisStandalone ann = mock(RedisStandalone.class);
      when(ann.id()).thenReturn(id);
      return ann;
    }
  }

  // ==================== supportsParameter() ====================

  @Nested
  @DisplayName("supportsParameter()")
  class SupportsParameter {

    private final RedisContainerExtension extension = new RedisContainerExtension();

    @Test
    @DisplayName("RedisConnectionInfo.class → true")
    void redisConnectionInfo() {
      // ARRANGE
      final ParameterContext paramCtx = mockParameterContext(RedisConnectionInfo.class, null);
      final ExtensionContext extCtx = mock(ExtensionContext.class);

      // ACT + ASSERT
      assertThat(extension.supportsParameter(paramCtx, extCtx)).isTrue();
    }

    @Test
    @DisplayName("StandaloneRedis.class → true")
    void standaloneRedis() {
      // ARRANGE
      final ParameterContext paramCtx = mockParameterContext(StandaloneRedis.class, null);
      final ExtensionContext extCtx = mock(ExtensionContext.class);

      // ACT + ASSERT
      assertThat(extension.supportsParameter(paramCtx, extCtx)).isTrue();
    }

    @Test
    @DisplayName("List<RedisConnectionInfo> → true")
    void listRedisConnectionInfo() {
      // ARRANGE
      final ParameterContext paramCtx =
          mockParameterContext(List.class, mockParameterizedType(RedisConnectionInfo.class));
      final ExtensionContext extCtx = mock(ExtensionContext.class);

      // ACT + ASSERT
      assertThat(extension.supportsParameter(paramCtx, extCtx)).isTrue();
    }

    @Test
    @DisplayName("List<StandaloneRedis> → true")
    void listStandaloneRedis() {
      // ARRANGE
      final ParameterContext paramCtx =
          mockParameterContext(List.class, mockParameterizedType(StandaloneRedis.class));
      final ExtensionContext extCtx = mock(ExtensionContext.class);

      // ACT + ASSERT
      assertThat(extension.supportsParameter(paramCtx, extCtx)).isTrue();
    }

    @Test
    @DisplayName("String.class → false")
    void stringType() {
      // ARRANGE
      final ParameterContext paramCtx = mockParameterContext(String.class, null);
      final ExtensionContext extCtx = mock(ExtensionContext.class);

      // ACT + ASSERT
      assertThat(extension.supportsParameter(paramCtx, extCtx)).isFalse();
    }

    @Test
    @DisplayName("List.class with non-generic type → false")
    void listWithNonGenericType() {
      // ARRANGE — raw List, parameterizedType returns the class itself (not ParameterizedType)
      final Parameter param = mock(Parameter.class);
      when(param.getType()).thenReturn((Class) List.class);
      when(param.getParameterizedType()).thenReturn(List.class); // not a ParameterizedType

      final ParameterContext paramCtx = mock(ParameterContext.class);
      when(paramCtx.getParameter()).thenReturn(param);
      final ExtensionContext extCtx = mock(ExtensionContext.class);

      // ACT + ASSERT
      assertThat(extension.supportsParameter(paramCtx, extCtx)).isFalse();
    }

    private ParameterContext mockParameterContext(
        final Class<?> type, final ParameterizedType parameterizedType) {
      final Parameter param = mock(Parameter.class);
      when(param.getType()).thenReturn((Class) type);
      if (parameterizedType != null) {
        when(param.getParameterizedType()).thenReturn(parameterizedType);
      } else {
        // lenient: only invoked when the code enters the List branch
        lenient().when(param.getParameterizedType()).thenReturn(type);
      }
      final ParameterContext paramCtx = mock(ParameterContext.class);
      when(paramCtx.getParameter()).thenReturn(param);
      return paramCtx;
    }

    private ParameterizedType mockParameterizedType(final Class<?> actualTypeArg) {
      final ParameterizedType pt = mock(ParameterizedType.class);
      when(pt.getActualTypeArguments()).thenReturn(new Type[] {actualTypeArg});
      return pt;
    }
  }

  // ==================== resolveParameter() ====================

  @Nested
  @DisplayName("resolveParameter()")
  class ResolveParameter {

    private final RedisContainerExtension extension = new RedisContainerExtension();

    @Test
    @DisplayName("StandaloneRedis type → returns StandaloneRedis")
    void standaloneRedisType() throws Exception {
      // ARRANGE
      final RedisConnectionInfo info = new RedisConnectionInfo("localhost", 6379);
      final Map<String, RedisConnectionInfo> instances = Map.of("single", info);
      final ExtensionContext ctx = contextWithStore(instances, null);
      getThreadLocal().set(ctx);

      final ParameterContext paramCtx = mockParameterContext(StandaloneRedis.class, null);
      final ExtensionContext extCtx = mock(ExtensionContext.class);

      // ACT
      final Object result = extension.resolveParameter(paramCtx, extCtx);

      // ASSERT
      assertThat(result).isInstanceOf(StandaloneRedis.class);
      final StandaloneRedis redis = (StandaloneRedis) result;
      assertThat(redis.host()).isEqualTo("localhost");
      assertThat(redis.port()).isEqualTo(6379);
    }

    @Test
    @DisplayName("List<StandaloneRedis> type → returns list of StandaloneRedis")
    void listStandaloneRedisType() throws Exception {
      // ARRANGE
      final RedisConnectionInfo info1 = new RedisConnectionInfo("h1", 6379);
      final RedisConnectionInfo info2 = new RedisConnectionInfo("h2", 6380);
      final Map<String, RedisConnectionInfo> instances = new LinkedHashMap<>();
      instances.put("first", info1);
      instances.put("second", info2);

      final RedisStandalone ann1 = mockAnnotation("first");
      final RedisStandalone ann2 = mockAnnotation("second");
      final RedisStandalone[] annotations = {ann1, ann2};
      final ExtensionContext ctx = contextWithStore(instances, annotations);
      getThreadLocal().set(ctx);

      final ParameterizedType pt = mock(ParameterizedType.class);
      when(pt.getActualTypeArguments()).thenReturn(new Type[] {StandaloneRedis.class});
      final ParameterContext paramCtx = mockParameterContext(List.class, pt);

      // ACT
      @SuppressWarnings("unchecked")
      final List<StandaloneRedis> result =
          (List<StandaloneRedis>)
              extension.resolveParameter(paramCtx, mock(ExtensionContext.class));

      // ASSERT
      assertThat(result).hasSize(2);
      assertThat(result).allSatisfy(r -> assertThat(r).isInstanceOf(StandaloneRedis.class));
    }

    @Test
    @DisplayName("List<RedisConnectionInfo> type → returns list of RedisConnectionInfo")
    void listRedisConnectionInfoType() throws Exception {
      // ARRANGE
      final RedisConnectionInfo info1 = new RedisConnectionInfo("h1", 6379);
      final Map<String, RedisConnectionInfo> instances = Map.of("first", info1);
      final RedisStandalone ann1 = mockAnnotation("first");
      final ExtensionContext ctx = contextWithStore(instances, new RedisStandalone[] {ann1});
      getThreadLocal().set(ctx);

      final ParameterizedType pt = mock(ParameterizedType.class);
      when(pt.getActualTypeArguments()).thenReturn(new Type[] {RedisConnectionInfo.class});
      final ParameterContext paramCtx = mockParameterContext(List.class, pt);

      // ACT
      @SuppressWarnings("unchecked")
      final List<RedisConnectionInfo> result =
          (List<RedisConnectionInfo>)
              extension.resolveParameter(paramCtx, mock(ExtensionContext.class));

      // ASSERT
      assertThat(result).hasSize(1).containsExactly(info1);
    }

    private ParameterContext mockParameterContext(
        final Class<?> type, final ParameterizedType parameterizedType) {
      final Parameter param = mock(Parameter.class);
      when(param.getType()).thenReturn((Class) type);
      if (parameterizedType != null) {
        when(param.getParameterizedType()).thenReturn(parameterizedType);
      } else {
        lenient().when(param.getParameterizedType()).thenReturn(type);
      }
      final ParameterContext paramCtx = mock(ParameterContext.class);
      when(paramCtx.getParameter()).thenReturn(param);
      return paramCtx;
    }

    private RedisStandalone mockAnnotation(final String id) {
      final RedisStandalone ann = mock(RedisStandalone.class);
      when(ann.id()).thenReturn(id);
      return ann;
    }
  }

  // ==================== RedisConnectionInfo ====================

  @Nested
  @DisplayName("RedisConnectionInfo")
  class RedisConnectionInfoTests {

    @Test
    @DisplayName("toString returns host:port")
    void toStringFormat() {
      final RedisConnectionInfo info = new RedisConnectionInfo("redis.local", 6379);
      assertThat(info.toString()).isEqualTo("redis.local:6379");
    }

    @Test
    @DisplayName("host and port accessors")
    void accessors() {
      final RedisConnectionInfo info = new RedisConnectionInfo("myhost", 6380);
      assertThat(info.getHost()).isEqualTo("myhost");
      assertThat(info.getPort()).isEqualTo(6380);
    }
  }

  // ==================== Store ====================

  @Nested
  @DisplayName("Store")
  class StoreTests {

    @Test
    @DisplayName("close() stops the container")
    void closeStopsContainer() {
      // ARRANGE
      final GenericContainer<?> container = mock(GenericContainer.class);
      final RedisConnectionInfo info = new RedisConnectionInfo("host", 1234);
      final Store store = new Store(container, info);

      // ACT
      store.close();

      // ASSERT
      org.mockito.Mockito.verify(container).stop();
    }

    @Test
    @DisplayName("getContainer and getConnectionInfo accessors")
    void accessors() {
      // ARRANGE
      final GenericContainer<?> container = mock(GenericContainer.class);
      final RedisConnectionInfo info = new RedisConnectionInfo("host", 1234);
      final Store store = new Store(container, info);

      // ASSERT
      assertThat(store.getContainer()).isSameAs(container);
      assertThat(store.getConnectionInfo()).isSameAs(info);
    }

    @Test
    @DisplayName("null container → NPE")
    void nullContainerThrows() {
      assertThatThrownBy(() -> new Store(null, new RedisConnectionInfo("h", 1)))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("null connectionInfo → NPE")
    void nullConnectionInfoThrows() {
      assertThatThrownBy(() -> new Store(mock(GenericContainer.class), null))
          .isInstanceOf(NullPointerException.class);
    }
  }
}
