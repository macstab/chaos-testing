/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.annotation.Resources;
import com.macstab.chaos.core.extension.MockChaosPlugin.*;
import com.macstab.chaos.core.syscall.LibchaosLib;

/**
 * Deep coverage tests for remaining uncovered paths in ChaosTestingExtension.
 *
 * <p>Targets:
 *
 * <ul>
 *   <li>createContainerInstance: null container from plugin, null connectionInfo
 *   <li>resolveParameter: matchCount > 1 error path
 *   <li>applyResourceConstraints: disk Linux path (System property override), invalid formats
 *   <li>storeConnectionInfo: base-type interface storage and retrieval
 *   <li>extractContainerAnnotations: @Repeatable unwrapping
 *   <li>discoverPlugins: post-load state validation
 *   <li>afterAll: containers=null path
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ChaosTestingExtension - Deep Coverage")
class ChaosTestingExtensionDeepCoverageTest {

  // ──────────────────────────────────────────────────────────────────────────
  // createContainerInstance — null container and null connectionInfo via reflection
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("createContainerInstance - null returns from plugin")
  class CreateContainerInstanceNullTest {

    @Test
    @DisplayName("null container from plugin throws ExtensionConfigurationException")
    void nullContainer_throwsExtensionConfigException() throws Exception {
      ChaosTestingExtension ext = new ChaosTestingExtension();
      Method m =
          ChaosTestingExtension.class.getDeclaredMethod(
              "createContainerInstance",
              ChaosPlugin.class,
              Annotation.class,
              Resources.class,
              LibchaosLib[].class,
              boolean.class);
      m.setAccessible(true);

      // Plugin that returns null container
      ChaosPlugin<MockContainer> nullContainerPlugin =
          new ChaosPlugin<>() {
            @Override
            public Class<MockContainer> annotationType() {
              return MockContainer.class;
            }

            @Override
            public GenericContainer<?> createContainer(MockContainer a) {
              return null;
            }

            @Override
            public Object createConnectionInfo(GenericContainer<?> c, MockContainer a) {
              return new Object();
            }

            @Override
            public Set<Class<?>> supportedParameterTypes() {
              return Set.of();
            }
          };

      MockContainer annotation = NoAnnotationClass.class.getAnnotation(MockContainer.class);

      assertThatThrownBy(
              () -> m.invoke(ext, nullContainerPlugin, annotation, null, new LibchaosLib[0], false))
          .cause()
          .isInstanceOf(ExtensionConfigurationException.class)
          .hasMessageContaining("null container");
    }

    @Test
    @DisplayName("null connectionInfo from plugin throws ExtensionConfigurationException")
    void nullConnectionInfo_throwsExtensionConfigException() throws Exception {
      ChaosTestingExtension ext = new ChaosTestingExtension();
      Method m =
          ChaosTestingExtension.class.getDeclaredMethod(
              "createContainerInstance",
              ChaosPlugin.class,
              Annotation.class,
              Resources.class,
              LibchaosLib[].class,
              boolean.class);
      m.setAccessible(true);

      // Plugin that returns a valid container but null connectionInfo
      ChaosPlugin<MockContainer> nullInfoPlugin = new NullConnectionInfoPlugin();

      MockContainer annotation = NoAnnotationClass.class.getAnnotation(MockContainer.class);

      assertThatThrownBy(
              () -> m.invoke(ext, nullInfoPlugin, annotation, null, new LibchaosLib[0], false))
          .cause()
          .isInstanceOf(ExtensionConfigurationException.class)
          .hasMessageContaining("null connection info");
    }

    @Test
    @DisplayName(
        "IllegalArgumentException from plugin is wrapped as ExtensionConfigurationException")
    void illegalArgFromPlugin_wrappedAsExtensionConfig() throws Exception {
      ChaosTestingExtension ext = new ChaosTestingExtension();
      Method m =
          ChaosTestingExtension.class.getDeclaredMethod(
              "createContainerInstance",
              ChaosPlugin.class,
              Annotation.class,
              Resources.class,
              LibchaosLib[].class,
              boolean.class);
      m.setAccessible(true);

      ChaosPlugin<MockContainer> throwingPlugin =
          new ChaosPlugin<>() {
            @Override
            public Class<MockContainer> annotationType() {
              return MockContainer.class;
            }

            @Override
            public GenericContainer<?> createContainer(MockContainer a) {
              throw new IllegalArgumentException("bad config value");
            }

            @Override
            public Object createConnectionInfo(GenericContainer<?> c, MockContainer a) {
              return null;
            }

            @Override
            public Set<Class<?>> supportedParameterTypes() {
              return Set.of();
            }
          };

      MockContainer annotation = NoAnnotationClass.class.getAnnotation(MockContainer.class);

      assertThatThrownBy(
              () -> m.invoke(ext, throwingPlugin, annotation, null, new LibchaosLib[0], false))
          .cause()
          .isInstanceOf(ExtensionConfigurationException.class)
          .hasMessageContaining("Invalid configuration");
    }

    @MockContainer(image = "alpine:latest")
    static class NoAnnotationClass {}
  }

  // ──────────────────────────────────────────────────────────────────────────
  // resolveParameter — matchCount > 1 (two containers, same type)
  // Using @Repeatable @MockContainer on a @Nested test class
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("resolveParameter - multi-match throws for single param")
  @MockContainer(image = "alpine:latest")
  @MockContainer(image = "alpine:latest")
  @ExtendWith(ChaosTestingExtension.class)
  class ResolveParameterMultiMatchTest {

    @Test
    @DisplayName("List<MockConnectionInfo> resolves all containers when multiple exist")
    void multipleContainers_listParameterResolvesAll(List<MockConnectionInfo> infos) {
      assertThat(infos).hasSize(2);
      assertThat(infos).allMatch(i -> i.getContainer().isRunning());
    }

    @Test
    @DisplayName("single MockConnectionInfo parameter throws when multiple containers exist")
    void multipleContainers_singleParamThrows(List<MockConnectionInfo> infos) throws Exception {
      // Drive resolveParameter directly with a store that has 2 containers of same type.
      // Build a fake container list with two MockConnectionInfo entries.
      ChaosTestingExtension ext = new ChaosTestingExtension();
      ParameterContext pc = singleMockInfoParamContext();

      // Populate a mock ExtensionContext.Store with two ContainerInstances via reflection.
      // Since ContainerInstance is private, use a store that returns a List with 2 infos
      // by injecting them as ConnectionInfo entries via a proxy list object.
      // Simpler: invoke resolveParameter with a store holding the real containers populated
      // by beforeAll (2 containers). Use the store accessor pattern:
      ExtensionContext.Store store = mock(ExtensionContext.Store.class);
      when(store.get(any())).thenReturn(buildTwoContainerList(infos));
      ExtensionContext ec = mock(ExtensionContext.class);
      when(ec.getStore(any())).thenReturn(store);
      when(ec.getUniqueId()).thenReturn("test-unique-id");

      assertThatThrownBy(() -> ext.resolveParameter(pc, ec))
          .isInstanceOf(ParameterResolutionException.class)
          .hasMessageContaining("Multiple containers found");
    }

    /** Build a List that looks like a ContainerInstance list with 2 matching infos. */
    private Object buildTwoContainerList(List<MockConnectionInfo> infos) throws Exception {
      // ContainerInstance is private inner class. Use reflection to construct two instances.
      Class<?> containerInstanceClass =
          Class.forName("com.macstab.chaos.core.extension.ChaosTestingExtension$ContainerInstance");
      java.lang.reflect.Constructor<?> ctor = containerInstanceClass.getDeclaredConstructors()[0];
      ctor.setAccessible(true);

      @SuppressWarnings("resource")
      GenericContainer<?> c = mock(GenericContainer.class);
      Annotation ann = NoAnnotationHolder.class.getAnnotation(MockContainer.class);

      Object ci1 = ctor.newInstance(c, ann, infos.get(0));
      Object ci2 = ctor.newInstance(c, ann, infos.get(0)); // same type, triggers matchCount > 1

      return java.util.List.of(ci1, ci2);
    }

    @MockContainer(image = "alpine:latest")
    static class NoAnnotationHolder {}
  }

  // ──────────────────────────────────────────────────────────────────────────
  // applyResourceConstraints — Linux disk path via System property override
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("applyResourceConstraints - disk size on Linux (System.property override)")
  class ApplyResourceConstraintsDiskLinuxTest {

    @Test
    @DisplayName("disk size applied when os.name=linux (coverage of Linux branch)")
    void diskSize_linuxBranch_appliedWithoutError() throws Exception {
      ChaosTestingExtension ext = new ChaosTestingExtension();
      Method m =
          ChaosTestingExtension.class.getDeclaredMethod(
              "applyResourceConstraints",
              GenericContainer.class,
              Annotation.class,
              Resources.class);
      m.setAccessible(true);

      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);

      MockContainer annotation = DiskAnnotationClass.class.getAnnotation(MockContainer.class);
      Resources resources = DiskAnnotationClass.class.getAnnotation(Resources.class);

      String originalOs = System.getProperty("os.name");
      try {
        System.setProperty("os.name", "Linux");
        // Should not throw — Linux path hits withStorageOpt
        assertThatCode(() -> m.invoke(ext, container, annotation, resources))
            .doesNotThrowAnyException();
      } finally {
        System.setProperty("os.name", originalOs);
      }
    }

    @Test
    @DisplayName("disk size with invalid format throws IllegalArgumentException")
    void diskSize_invalidFormat_throwsIllegalArgument() throws Exception {
      ChaosTestingExtension ext = new ChaosTestingExtension();
      Method m =
          ChaosTestingExtension.class.getDeclaredMethod(
              "applyResourceConstraints",
              GenericContainer.class,
              Annotation.class,
              Resources.class);
      m.setAccessible(true);

      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);

      MockContainer annotation = InvalidDiskClass.class.getAnnotation(MockContainer.class);
      Resources resources = InvalidDiskClass.class.getAnnotation(Resources.class);

      assertThatThrownBy(() -> m.invoke(ext, container, annotation, resources))
          .cause()
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("invalid memory format in applyResourceConstraints throws wrapped IAE")
    void memory_invalidFormat_throwsWrappedIAE() throws Exception {
      ChaosTestingExtension ext = new ChaosTestingExtension();
      Method m =
          ChaosTestingExtension.class.getDeclaredMethod(
              "applyResourceConstraints",
              GenericContainer.class,
              Annotation.class,
              Resources.class);
      m.setAccessible(true);

      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);

      MockContainer annotation = InvalidMemoryClass.class.getAnnotation(MockContainer.class);
      Resources resources = InvalidMemoryClass.class.getAnnotation(Resources.class);

      assertThatThrownBy(() -> m.invoke(ext, container, annotation, resources))
          .cause()
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("invalid cpu format in applyResourceConstraints throws wrapped IAE")
    void cpu_invalidFormat_throwsWrappedIAE() throws Exception {
      ChaosTestingExtension ext = new ChaosTestingExtension();
      Method m =
          ChaosTestingExtension.class.getDeclaredMethod(
              "applyResourceConstraints",
              GenericContainer.class,
              Annotation.class,
              Resources.class);
      m.setAccessible(true);

      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);

      MockContainer annotation = InvalidCpuClass.class.getAnnotation(MockContainer.class);
      Resources resources = InvalidCpuClass.class.getAnnotation(Resources.class);

      assertThatThrownBy(() -> m.invoke(ext, container, annotation, resources))
          .cause()
          .isInstanceOf(IllegalArgumentException.class);
    }

    @MockContainer(image = "alpine:latest")
    @Resources(diskSize = "5G")
    static class DiskAnnotationClass {}

    @MockContainer(image = "alpine:latest")
    @Resources(diskSize = "5GB")
    static class InvalidDiskClass {}

    @MockContainer(image = "alpine:latest")
    @Resources(memory = "512MB")
    static class InvalidMemoryClass {}

    @MockContainer(image = "alpine:latest")
    @Resources(cpus = "2.5.5")
    static class InvalidCpuClass {}
  }

  // ──────────────────────────────────────────────────────────────────────────
  // storeConnectionInfo — base-type retrieval via getConnectionInfoByBaseType
  // and getAllConnectionInfoByBaseType
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("storeConnectionInfo - base-type interface storage")
  @MockContainer(image = "alpine:latest")
  @ExtendWith(ChaosTestingExtension.class)
  class StoreConnectionInfoBaseTypeTest {

    @Test
    @DisplayName("getConnectionInfoByBaseType returns info when interface is a base type")
    void getConnectionInfoByBaseType_returnsInfo(MockConnectionInfo info) {
      // MockConnectionInfo implements MockConnectionBase — stored under that base type
      Object stored =
          ChaosTestingExtension.getConnectionInfoByBaseType(MockConnectionBase.class, "default");
      assertThat(stored).isInstanceOf(MockConnectionBase.class);
      assertThat(stored).isEqualTo(info);
    }

    @Test
    @DisplayName("getAllConnectionInfoByBaseType returns all entries for base type")
    void getAllConnectionInfoByBaseType_returnsAll(MockConnectionInfo info) {
      List<Object> all =
          ChaosTestingExtension.getAllConnectionInfoByBaseType(MockConnectionBase.class);
      assertThat(all).hasSize(1);
      assertThat(all.get(0)).isEqualTo(info);
    }

    @Test
    @DisplayName("getConnectionInfoByBaseType throws for unknown id on known base type")
    void getConnectionInfoByBaseType_unknownId_throws() {
      assertThatThrownBy(
              () ->
                  ChaosTestingExtension.getConnectionInfoByBaseType(
                      MockConnectionBase.class, "nonexistent"))
          .isInstanceOf(java.util.NoSuchElementException.class)
          .hasMessageContaining("nonexistent");
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // extractContainerAnnotations — @Repeatable unwrapping
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("extractContainerAnnotations - @Repeatable unwrapping")
  class ExtractRepeatableTest {

    @Test
    @DisplayName("two @MockContainer on class extracted as two separate annotations")
    void twoMockContainers_extractedSeparately() throws Exception {
      ChaosTestingExtension ext = new ChaosTestingExtension();
      Method m =
          ChaosTestingExtension.class.getDeclaredMethod("extractContainerAnnotations", Class.class);
      m.setAccessible(true);

      @SuppressWarnings("unchecked")
      List<Annotation> result = (List<Annotation>) m.invoke(ext, TwoContainerClass.class);

      assertThat(result).hasSize(2);
      assertThat(result).allMatch(a -> a.annotationType().equals(MockContainer.class));
    }

    @MockContainer(image = "alpine:latest")
    @MockContainer(image = "alpine:latest")
    static class TwoContainerClass {}
  }

  // ──────────────────────────────────────────────────────────────────────────
  // afterAll — null containers in store (no beforeAll ran)
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("afterAll - null containers in store")
  class AfterAllNullContainersTest {

    @Test
    @DisplayName("afterAll with null containers in store does not throw")
    void afterAll_nullContainers_doesNotThrow() throws Exception {
      ChaosTestingExtension ext = new ChaosTestingExtension();

      ExtensionContext ec = mock(ExtensionContext.class);
      ExtensionContext.Store store = mock(ExtensionContext.Store.class);
      when(store.get(any())).thenReturn(null); // null containers
      when(ec.getStore(any())).thenReturn(store);
      when(ec.getRequiredTestClass()).thenReturn((Class) AfterAllNullContainersTest.class);

      assertThatCode(() -> ext.afterAll(ec)).doesNotThrowAnyException();
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // beforeAll — no annotations on test class (empty path)
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("beforeAll - class with no container annotations")
  class BeforeAllNoAnnotationsTest {

    @Test
    @DisplayName("beforeAll with empty annotation list stores empty container list")
    void beforeAll_noAnnotations_storesEmptyList() throws Exception {
      ChaosTestingExtension ext = new ChaosTestingExtension();

      ExtensionContext.Store store = mock(ExtensionContext.Store.class);
      ExtensionContext ec = mock(ExtensionContext.class);
      when(ec.getStore(any())).thenReturn(store);
      when(ec.getRequiredTestClass()).thenReturn((Class) PlainClass.class);

      assertThatCode(() -> ext.beforeAll(ec)).doesNotThrowAnyException();
      // Four empty lists stored: containers, persistent L1 handles, L2 handles, L3 handles
      verify(store, times(4))
          .put(any(), argThat(arg -> arg instanceof List && ((List<?>) arg).isEmpty()));
    }

    static class PlainClass {}
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Helpers
  // ──────────────────────────────────────────────────────────────────────────

  @SuppressWarnings("unused")
  static class SingleMockInfoHolder {
    void method(MockConnectionInfo p) {}
  }

  private static ParameterContext singleMockInfoParamContext() throws Exception {
    ParameterContext pc = mock(ParameterContext.class);
    when(pc.getParameter())
        .thenReturn(
            SingleMockInfoHolder.class.getDeclaredMethod("method", MockConnectionInfo.class)
                .getParameters()[0]);
    return pc;
  }

  /** Plugin that returns a valid (started) container but null connectionInfo. */
  static final class NullConnectionInfoPlugin implements ChaosPlugin<MockContainer> {
    @Override
    public Class<MockContainer> annotationType() {
      return MockContainer.class;
    }

    @Override
    public GenericContainer<?> createContainer(final MockContainer annotation) {
      @SuppressWarnings("resource")
      final GenericContainer<?> c =
          new GenericContainer<>(org.testcontainers.utility.DockerImageName.parse("alpine:latest"));
      c.withCommand("sleep", "infinity");
      c.waitingFor(org.testcontainers.containers.wait.strategy.Wait.forSuccessfulCommand("ls -la"));
      return c;
    }

    @Override
    public Object createConnectionInfo(final GenericContainer<?> c, final MockContainer a) {
      return null; // triggers null-check in createContainerInstance
    }

    @Override
    public Set<Class<?>> supportedParameterTypes() {
      return Set.of();
    }
  }
}
