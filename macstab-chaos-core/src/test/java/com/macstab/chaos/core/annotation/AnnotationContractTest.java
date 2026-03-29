/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.annotation;

import static org.assertj.core.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;

import org.junit.jupiter.api.*;

import com.github.dockerjava.api.model.Capability;
import com.macstab.chaos.core.platform.Tool;

/**
 * Annotation contract tests — verifies retention, targets, and default values for all public
 * annotations defined in the chaos-core annotation package.
 *
 * <p>Structure:
 *
 * <ul>
 *   <li>{@code RetentionAndTargetTest} — meta contract for all 6 annotations
 *   <li>{@code ResourcesContractTest} — attribute defaults + combinations
 *   <li>{@code InstallPackagesContractTest} — value/verify/target defaults + combinations
 *   <li>{@code InstallToolsContractTest} — value/verify/target defaults + combinations
 *   <li>{@code RequireCapabilityContractTest} — value/target defaults + combinations
 *   <li>{@code ConfigureContainerContractTest} — all attribute defaults
 *   <li>{@code ClassLevelCombinationTest} — all four stacked on a class
 *   <li>{@code FieldLevelCombinationTest} — multiple fields, diverse combos
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("Annotation Contracts")
class AnnotationContractTest {

  // ──────────────────────────────────────────────────────────────────────────
  // Retention and Target contracts
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("Retention and Target")
  class RetentionAndTargetTest {

    @Test
    @DisplayName("@ChaosTest has RUNTIME retention and ANNOTATION_TYPE target")
    void chaosTest_retentionAndTarget() {
      assertRetention(ChaosTest.class, RetentionPolicy.RUNTIME);
      assertTarget(ChaosTest.class, ElementType.ANNOTATION_TYPE);
    }

    @Test
    @DisplayName("@Resources has RUNTIME retention and TYPE target")
    void resources_retentionAndTarget() {
      assertRetention(Resources.class, RetentionPolicy.RUNTIME);
      assertTarget(Resources.class, ElementType.TYPE);
    }

    @Test
    @DisplayName("@InstallPackages has RUNTIME retention and TYPE + FIELD targets")
    void installPackages_retentionAndTarget() {
      assertRetention(InstallPackages.class, RetentionPolicy.RUNTIME);
      assertTarget(InstallPackages.class, ElementType.TYPE, ElementType.FIELD);
    }

    @Test
    @DisplayName("@InstallTools has RUNTIME retention and TYPE + FIELD targets")
    void installTools_retentionAndTarget() {
      assertRetention(InstallTools.class, RetentionPolicy.RUNTIME);
      assertTarget(InstallTools.class, ElementType.TYPE, ElementType.FIELD);
    }

    @Test
    @DisplayName("@RequireCapability has RUNTIME retention and TYPE + FIELD targets")
    void requireCapability_retentionAndTarget() {
      assertRetention(RequireCapability.class, RetentionPolicy.RUNTIME);
      assertTarget(RequireCapability.class, ElementType.TYPE, ElementType.FIELD);
    }

    @Test
    @DisplayName("@ConfigureContainer has RUNTIME retention and FIELD target")
    void configureContainer_retentionAndTarget() {
      assertRetention(ConfigureContainer.class, RetentionPolicy.RUNTIME);
      assertTarget(ConfigureContainer.class, ElementType.FIELD);
    }

    private void assertRetention(Class<?> ann, RetentionPolicy expected) {
      Retention r = ann.getAnnotation(Retention.class);
      assertThat(r).as("@Retention on " + ann.getSimpleName()).isNotNull();
      assertThat(r.value()).isEqualTo(expected);
    }

    private void assertTarget(Class<?> ann, ElementType... expected) {
      Target t = ann.getAnnotation(Target.class);
      assertThat(t).as("@Target on " + ann.getSimpleName()).isNotNull();
      assertThat(t.value()).containsExactlyInAnyOrder(expected);
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // @Resources — defaults and combinations
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("@Resources - defaults and combinations")
  class ResourcesContractTest {

    @Test
    @DisplayName("all defaults are empty strings")
    void defaults_areEmptyStrings() throws Exception {
      Resources r = DefaultResources.class.getAnnotation(Resources.class);
      assertThat(r.memory()).isEmpty();
      assertThat(r.cpus()).isEmpty();
      assertThat(r.diskSize()).isEmpty();
    }

    @Test
    @DisplayName("memory-only produces correct value")
    void memoryOnly() {
      Resources r = MemoryOnlyResources.class.getAnnotation(Resources.class);
      assertThat(r.memory()).isEqualTo("512M");
      assertThat(r.cpus()).isEmpty();
      assertThat(r.diskSize()).isEmpty();
    }

    @Test
    @DisplayName("cpu-only produces correct value")
    void cpuOnly() {
      Resources r = CpuOnlyResources.class.getAnnotation(Resources.class);
      assertThat(r.cpus()).isEqualTo("2");
      assertThat(r.memory()).isEmpty();
    }

    @Test
    @DisplayName("memory + cpu combination")
    void memoryCpuCombo() {
      Resources r = MemoryCpuResources.class.getAnnotation(Resources.class);
      assertThat(r.memory()).isEqualTo("1G");
      assertThat(r.cpus()).isEqualTo("0.5");
      assertThat(r.diskSize()).isEmpty();
    }

    @Test
    @DisplayName("all three constraints set")
    void allConstraints() {
      Resources r = AllConstraintsResources.class.getAnnotation(Resources.class);
      assertThat(r.memory()).isEqualTo("256M");
      assertThat(r.cpus()).isEqualTo("4");
      assertThat(r.diskSize()).isEqualTo("10G");
    }

    @Resources
    static class DefaultResources {}

    @Resources(memory = "512M")
    static class MemoryOnlyResources {}

    @Resources(cpus = "2")
    static class CpuOnlyResources {}

    @Resources(memory = "1G", cpus = "0.5")
    static class MemoryCpuResources {}

    @Resources(memory = "256M", cpus = "4", diskSize = "10G")
    static class AllConstraintsResources {}
  }

  // ──────────────────────────────────────────────────────────────────────────
  // @InstallPackages — defaults and combinations
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("@InstallPackages - defaults and combinations")
  class InstallPackagesContractTest {

    @Test
    @DisplayName("verify defaults to true, target defaults to empty")
    void defaults() throws Exception {
      InstallPackages a = SinglePackage.class.getAnnotation(InstallPackages.class);
      assertThat(a.value()).containsExactly("curl");
      assertThat(a.verify()).isTrue();
      assertThat(a.target()).isEmpty();
    }

    @Test
    @DisplayName("verify=false suppresses verification")
    void verifyFalse() {
      InstallPackages a = NoVerifyPackage.class.getAnnotation(InstallPackages.class);
      assertThat(a.verify()).isFalse();
    }

    @Test
    @DisplayName("target selects specific container")
    void targetedPackage() {
      InstallPackages a = TargetedPackage.class.getAnnotation(InstallPackages.class);
      assertThat(a.target()).isEqualTo("master");
      assertThat(a.value()).containsExactly("tcpdump");
    }

    @Test
    @DisplayName("multiple packages in value array")
    void multiplePackages() {
      InstallPackages a = MultiPackage.class.getAnnotation(InstallPackages.class);
      assertThat(a.value()).containsExactly("tcpdump", "netcat", "strace");
    }

    @Test
    @DisplayName("field-level annotation is present on field")
    void fieldLevelAnnotation() throws Exception {
      Field f = FieldAnnotationHolder.class.getDeclaredField("container");
      InstallPackages a = f.getAnnotation(InstallPackages.class);
      assertThat(a).isNotNull();
      assertThat(a.value()).containsExactly("wget");
    }

    @InstallPackages("curl")
    static class SinglePackage {}

    @InstallPackages(value = "curl", verify = false)
    static class NoVerifyPackage {}

    @InstallPackages(value = "tcpdump", target = "master")
    static class TargetedPackage {}

    @InstallPackages({"tcpdump", "netcat", "strace"})
    static class MultiPackage {}

    static class FieldAnnotationHolder {
      @InstallPackages("wget")
      Object container;
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // @InstallTools — defaults and combinations
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("@InstallTools - defaults and combinations")
  class InstallToolsContractTest {

    @Test
    @DisplayName("single tool, verify defaults to true, target defaults to empty")
    void defaults() {
      InstallTools a = SingleTool.class.getAnnotation(InstallTools.class);
      assertThat(a.value()).containsExactly(Tool.CURL);
      assertThat(a.verify()).isTrue();
      assertThat(a.target()).isEmpty();
    }

    @Test
    @DisplayName("verify=false suppresses verification")
    void verifyFalse() {
      InstallTools a = NoVerifyTool.class.getAnnotation(InstallTools.class);
      assertThat(a.verify()).isFalse();
      assertThat(a.value()).containsExactly(Tool.CA_CERTIFICATES);
    }

    @Test
    @DisplayName("target selects specific container")
    void targetedTool() {
      InstallTools a = TargetedTool.class.getAnnotation(InstallTools.class);
      assertThat(a.target()).isEqualTo("replica");
      assertThat(a.value()).containsExactly(Tool.IPTABLES);
    }

    @Test
    @DisplayName("multiple tools in value array")
    void multipleTools() {
      InstallTools a = MultiTool.class.getAnnotation(InstallTools.class);
      assertThat(a.value())
          .containsExactlyInAnyOrder(Tool.CURL, Tool.IPTABLES, Tool.PROCPS, Tool.STRESS_NG);
    }

    @Test
    @DisplayName("field-level annotation is present on field")
    void fieldLevelAnnotation() throws Exception {
      Field f = FieldAnnotationHolder.class.getDeclaredField("container");
      InstallTools a = f.getAnnotation(InstallTools.class);
      assertThat(a).isNotNull();
      assertThat(a.value()).containsExactly(Tool.PYTHON);
    }

    @InstallTools(Tool.CURL)
    static class SingleTool {}

    @InstallTools(value = Tool.CA_CERTIFICATES, verify = false)
    static class NoVerifyTool {}

    @InstallTools(value = Tool.IPTABLES, target = "replica")
    static class TargetedTool {}

    @InstallTools({Tool.CURL, Tool.IPTABLES, Tool.PROCPS, Tool.STRESS_NG})
    static class MultiTool {}

    static class FieldAnnotationHolder {
      @InstallTools(Tool.PYTHON)
      Object container;
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // @RequireCapability — defaults and combinations
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("@RequireCapability - defaults and combinations")
  class RequireCapabilityContractTest {

    @Test
    @DisplayName("single capability, target defaults to empty")
    void defaults() {
      RequireCapability a = NetAdminOnly.class.getAnnotation(RequireCapability.class);
      assertThat(a.value()).containsExactly(Capability.NET_ADMIN);
      assertThat(a.target()).isEmpty();
    }

    @Test
    @DisplayName("multiple capabilities in value array")
    void multipleCapabilities() {
      RequireCapability a = MultiCapability.class.getAnnotation(RequireCapability.class);
      assertThat(a.value()).containsExactlyInAnyOrder(Capability.NET_ADMIN, Capability.SYS_ADMIN);
    }

    @Test
    @DisplayName("target selects specific container")
    void targetedCapability() {
      RequireCapability a = TargetedCapability.class.getAnnotation(RequireCapability.class);
      assertThat(a.target()).isEqualTo("chaos-node");
      assertThat(a.value()).containsExactly(Capability.SYS_PTRACE);
    }

    @Test
    @DisplayName("field-level annotation is present on field")
    void fieldLevelAnnotation() throws Exception {
      Field f = FieldAnnotationHolder.class.getDeclaredField("container");
      RequireCapability a = f.getAnnotation(RequireCapability.class);
      assertThat(a).isNotNull();
      assertThat(a.value()).containsExactly(Capability.NET_ADMIN);
    }

    @RequireCapability(Capability.NET_ADMIN)
    static class NetAdminOnly {}

    @RequireCapability({Capability.NET_ADMIN, Capability.SYS_ADMIN})
    static class MultiCapability {}

    @RequireCapability(value = Capability.SYS_PTRACE, target = "chaos-node")
    static class TargetedCapability {}

    static class FieldAnnotationHolder {
      @RequireCapability(Capability.NET_ADMIN)
      Object container;
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // @ConfigureContainer — all attribute defaults
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("@ConfigureContainer - defaults and combinations")
  class ConfigureContainerContractTest {

    @Test
    @DisplayName("all defaults are empty/sentinel values")
    void allDefaults() throws Exception {
      Field f = AllDefaultsHolder.class.getDeclaredField("container");
      ConfigureContainer a = f.getAnnotation(ConfigureContainer.class);
      assertThat(a.memory()).isEmpty();
      assertThat(a.cpus()).isEqualTo(-1);
      assertThat(a.diskSize()).isEmpty();
      assertThat(a.cpuShares()).isEqualTo(-1);
      assertThat(a.memorySwap()).isEmpty();
    }

    @Test
    @DisplayName("memory-only combination")
    void memoryOnly() throws Exception {
      Field f = MemoryOnlyHolder.class.getDeclaredField("container");
      ConfigureContainer a = f.getAnnotation(ConfigureContainer.class);
      assertThat(a.memory()).isEqualTo("512M");
      assertThat(a.cpus()).isEqualTo(-1);
    }

    @Test
    @DisplayName("cpu-only combination")
    void cpuOnly() throws Exception {
      Field f = CpuOnlyHolder.class.getDeclaredField("container");
      ConfigureContainer a = f.getAnnotation(ConfigureContainer.class);
      assertThat(a.cpus()).isEqualTo(4);
      assertThat(a.memory()).isEmpty();
    }

    @Test
    @DisplayName("all attributes set")
    void allAttributesSet() throws Exception {
      Field f = AllAttributesHolder.class.getDeclaredField("container");
      ConfigureContainer a = f.getAnnotation(ConfigureContainer.class);
      assertThat(a.memory()).isEqualTo("1G");
      assertThat(a.cpus()).isEqualTo(2);
      assertThat(a.diskSize()).isEqualTo("20G");
      assertThat(a.cpuShares()).isEqualTo(512);
      assertThat(a.memorySwap()).isEqualTo("2G");
    }

    @Test
    @DisplayName("cpuShares-only combination")
    void cpuSharesOnly() throws Exception {
      Field f = CpuSharesHolder.class.getDeclaredField("container");
      ConfigureContainer a = f.getAnnotation(ConfigureContainer.class);
      assertThat(a.cpuShares()).isEqualTo(1024);
      assertThat(a.cpus()).isEqualTo(-1);
    }

    @Test
    @DisplayName("memorySwap-only combination")
    void memorySwapOnly() throws Exception {
      Field f = MemorySwapHolder.class.getDeclaredField("container");
      ConfigureContainer a = f.getAnnotation(ConfigureContainer.class);
      assertThat(a.memorySwap()).isEqualTo("4G");
      assertThat(a.memory()).isEmpty();
    }

    static class AllDefaultsHolder {
      @ConfigureContainer Object container;
    }

    static class MemoryOnlyHolder {
      @ConfigureContainer(memory = "512M")
      Object container;
    }

    static class CpuOnlyHolder {
      @ConfigureContainer(cpus = 4)
      Object container;
    }

    static class CpuSharesHolder {
      @ConfigureContainer(cpuShares = 1024)
      Object container;
    }

    static class MemorySwapHolder {
      @ConfigureContainer(memorySwap = "4G")
      Object container;
    }

    static class AllAttributesHolder {
      @ConfigureContainer(
          memory = "1G",
          cpus = 2,
          diskSize = "20G",
          cpuShares = 512,
          memorySwap = "2G")
      Object container;
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Class-level combination: all four stacked on one class
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("Class-level combination: all annotations stacked")
  class ClassLevelCombinationTest {

    @Test
    @DisplayName("all four annotations readable on same class")
    void allAnnotationsReadable() {
      Resources res = FullyAnnotatedClass.class.getAnnotation(Resources.class);
      InstallPackages pkg = FullyAnnotatedClass.class.getAnnotation(InstallPackages.class);
      InstallTools tools = FullyAnnotatedClass.class.getAnnotation(InstallTools.class);
      RequireCapability cap = FullyAnnotatedClass.class.getAnnotation(RequireCapability.class);

      assertThat(res).isNotNull();
      assertThat(pkg).isNotNull();
      assertThat(tools).isNotNull();
      assertThat(cap).isNotNull();
    }

    @Test
    @DisplayName("all attribute values are correct on fully annotated class")
    void allAttributeValues() {
      Resources res = FullyAnnotatedClass.class.getAnnotation(Resources.class);
      assertThat(res.memory()).isEqualTo("512M");
      assertThat(res.cpus()).isEqualTo("2");
      assertThat(res.diskSize()).isEqualTo("5G");

      InstallPackages pkg = FullyAnnotatedClass.class.getAnnotation(InstallPackages.class);
      assertThat(pkg.value()).containsExactly("tcpdump");
      assertThat(pkg.verify()).isTrue();

      InstallTools tools = FullyAnnotatedClass.class.getAnnotation(InstallTools.class);
      assertThat(tools.value()).containsExactlyInAnyOrder(Tool.CURL, Tool.IPTABLES);
      assertThat(tools.verify()).isTrue();

      RequireCapability cap = FullyAnnotatedClass.class.getAnnotation(RequireCapability.class);
      assertThat(cap.value()).containsExactly(Capability.NET_ADMIN);
    }

    @Test
    @DisplayName("minimal class-level: only @Resources present")
    void minimalClassLevel() {
      assertThat(MinimalClass.class.getAnnotation(Resources.class)).isNotNull();
      assertThat(MinimalClass.class.getAnnotation(InstallPackages.class)).isNull();
      assertThat(MinimalClass.class.getAnnotation(InstallTools.class)).isNull();
      assertThat(MinimalClass.class.getAnnotation(RequireCapability.class)).isNull();
    }

    @Resources(memory = "512M", cpus = "2", diskSize = "5G")
    @InstallPackages("tcpdump")
    @InstallTools({Tool.CURL, Tool.IPTABLES})
    @RequireCapability(Capability.NET_ADMIN)
    static class FullyAnnotatedClass {}

    @Resources(memory = "128M")
    static class MinimalClass {}
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Field-level combinations: multiple fields, diverse annotation combos
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("Field-level combinations: multiple fields, diverse combos")
  class FieldLevelCombinationTest {

    @Test
    @DisplayName("primary container: InstallPackages + RequireCapability")
    void primaryContainer() throws Exception {
      Field f = MultiFieldHolder.class.getDeclaredField("primary");

      InstallPackages pkg = f.getAnnotation(InstallPackages.class);
      assertThat(pkg.value()).containsExactly("curl", "tcpdump");
      assertThat(pkg.verify()).isTrue();

      RequireCapability cap = f.getAnnotation(RequireCapability.class);
      assertThat(cap.value()).containsExactly(Capability.NET_ADMIN);
    }

    @Test
    @DisplayName("secondary container: InstallTools + ConfigureContainer")
    void secondaryContainer() throws Exception {
      Field f = MultiFieldHolder.class.getDeclaredField("secondary");

      InstallTools tools = f.getAnnotation(InstallTools.class);
      assertThat(tools.value()).containsExactly(Tool.STRESS_NG);
      assertThat(tools.verify()).isTrue();

      ConfigureContainer cfg = f.getAnnotation(ConfigureContainer.class);
      assertThat(cfg.memory()).isEqualTo("256M");
      assertThat(cfg.cpus()).isEqualTo(2);
    }

    @Test
    @DisplayName("tertiary container: all four field annotations stacked")
    void tertiaryContainer() throws Exception {
      Field f = MultiFieldHolder.class.getDeclaredField("tertiary");

      assertThat(f.getAnnotation(InstallPackages.class)).isNotNull();
      assertThat(f.getAnnotation(InstallTools.class)).isNotNull();
      assertThat(f.getAnnotation(RequireCapability.class)).isNotNull();
      assertThat(f.getAnnotation(ConfigureContainer.class)).isNotNull();

      assertThat(f.getAnnotation(InstallPackages.class).value()).containsExactly("netcat");
      assertThat(f.getAnnotation(InstallTools.class).value()).containsExactly(Tool.PYTHON);
      assertThat(f.getAnnotation(RequireCapability.class).value())
          .containsExactly(Capability.SYS_ADMIN);
      assertThat(f.getAnnotation(ConfigureContainer.class).diskSize()).isEqualTo("10G");
    }

    @Test
    @DisplayName("bare container: no field annotations")
    void bareContainer() throws Exception {
      Field f = MultiFieldHolder.class.getDeclaredField("bare");

      assertThat(f.getAnnotation(InstallPackages.class)).isNull();
      assertThat(f.getAnnotation(InstallTools.class)).isNull();
      assertThat(f.getAnnotation(RequireCapability.class)).isNull();
      assertThat(f.getAnnotation(ConfigureContainer.class)).isNull();
    }

    @Test
    @DisplayName("no-verify combo: verify=false on both InstallPackages and InstallTools")
    void noVerifyCombo() throws Exception {
      Field f = MultiFieldHolder.class.getDeclaredField("noVerify");

      assertThat(f.getAnnotation(InstallPackages.class).verify()).isFalse();
      assertThat(f.getAnnotation(InstallTools.class).verify()).isFalse();
    }

    @Test
    @DisplayName("targeted combo: target set on both InstallPackages and InstallTools")
    void targetedCombo() throws Exception {
      Field f = MultiFieldHolder.class.getDeclaredField("targeted");

      assertThat(f.getAnnotation(InstallPackages.class).target()).isEqualTo("sidecar");
      assertThat(f.getAnnotation(InstallTools.class).target()).isEqualTo("sidecar");
    }

    static class MultiFieldHolder {
      @InstallPackages({"curl", "tcpdump"})
      @RequireCapability(Capability.NET_ADMIN)
      Object primary;

      @InstallTools(Tool.STRESS_NG)
      @ConfigureContainer(memory = "256M", cpus = 2)
      Object secondary;

      @InstallPackages("netcat")
      @InstallTools(Tool.PYTHON)
      @RequireCapability(Capability.SYS_ADMIN)
      @ConfigureContainer(diskSize = "10G")
      Object tertiary;

      Object bare;

      @InstallPackages(value = "ca-certificates", verify = false)
      @InstallTools(value = Tool.CA_CERTIFICATES, verify = false)
      Object noVerify;

      @InstallPackages(value = "wget", target = "sidecar")
      @InstallTools(value = Tool.IPROUTE, target = "sidecar")
      Object targeted;
    }
  }
}
