/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.annotation.InstallPackages;
import com.macstab.chaos.core.annotation.InstallTools;
import com.macstab.chaos.core.platform.Tool;

/**
 * Unit tests for {@link AnnotationMerger}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("AnnotationMerger")
class AnnotationMergerTest {

  @Nested
  @DisplayName("mergePackages")
  class MergePackages {

    @Test
    @DisplayName("should merge packages from multiple annotations")
    void shouldMergePackagesFromMultipleAnnotations() {
      final var annotation1 = createInstallPackages("curl", "tcpdump");
      final var annotation2 = createInstallPackages("netcat", "strace");

      final var result = AnnotationMerger.mergePackages(List.of(annotation1, annotation2));

      assertThat(result).containsExactly("curl", "tcpdump", "netcat", "strace");
    }

    @Test
    @DisplayName("should deduplicate packages")
    void shouldDeduplicatePackages() {
      final var annotation1 = createInstallPackages("curl", "tcpdump");
      final var annotation2 = createInstallPackages("tcpdump", "netcat");

      final var result = AnnotationMerger.mergePackages(List.of(annotation1, annotation2));

      assertThat(result).containsExactly("curl", "tcpdump", "netcat");
    }

    @Test
    @DisplayName("should preserve insertion order")
    void shouldPreserveInsertionOrder() {
      final var annotation1 = createInstallPackages("zzz", "aaa");
      final var annotation2 = createInstallPackages("mmm");

      final var result = AnnotationMerger.mergePackages(List.of(annotation1, annotation2));

      assertThat(result).containsExactly("zzz", "aaa", "mmm");
    }

    @Test
    @DisplayName("should handle empty annotations list")
    void shouldHandleEmptyAnnotationsList() {
      final var result = AnnotationMerger.mergePackages(List.of());

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should ignore non-InstallPackages annotations")
    void shouldIgnoreNonInstallPackagesAnnotations() {
      final var annotation1 = createInstallPackages("curl");
      final var annotation2 = createInstallTools(Tool.IPTABLES);

      final var result = AnnotationMerger.mergePackages(List.of(annotation1, annotation2));

      assertThat(result).containsExactly("curl");
    }
  }

  @Nested
  @DisplayName("mergeTools")
  class MergeTools {

    @Test
    @DisplayName("should merge tools from multiple annotations")
    void shouldMergeToolsFromMultipleAnnotations() {
      final var annotation1 = createInstallTools(Tool.CURL, Tool.IPTABLES);
      final var annotation2 = createInstallTools(Tool.PROCPS, Tool.STRESS_NG);

      final var result = AnnotationMerger.mergeTools(List.of(annotation1, annotation2));

      assertThat(result).containsExactly(Tool.CURL, Tool.IPTABLES, Tool.PROCPS, Tool.STRESS_NG);
    }

    @Test
    @DisplayName("should deduplicate tools")
    void shouldDeduplicateTools() {
      final var annotation1 = createInstallTools(Tool.CURL, Tool.IPTABLES);
      final var annotation2 = createInstallTools(Tool.IPTABLES, Tool.PROCPS);

      final var result = AnnotationMerger.mergeTools(List.of(annotation1, annotation2));

      assertThat(result).containsExactly(Tool.CURL, Tool.IPTABLES, Tool.PROCPS);
    }

    @Test
    @DisplayName("should preserve insertion order")
    void shouldPreserveInsertionOrder() {
      final var annotation1 = createInstallTools(Tool.STRESS_NG, Tool.CURL);
      final var annotation2 = createInstallTools(Tool.IPTABLES);

      final var result = AnnotationMerger.mergeTools(List.of(annotation1, annotation2));

      assertThat(result).containsExactly(Tool.STRESS_NG, Tool.CURL, Tool.IPTABLES);
    }

    @Test
    @DisplayName("should handle empty annotations list")
    void shouldHandleEmptyAnnotationsList() {
      final var result = AnnotationMerger.mergeTools(List.of());

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should ignore non-InstallTools annotations")
    void shouldIgnoreNonInstallToolsAnnotations() {
      final var annotation1 = createInstallTools(Tool.CURL);
      final var annotation2 = createInstallPackages("tcpdump");

      final var result = AnnotationMerger.mergeTools(List.of(annotation1, annotation2));

      assertThat(result).containsExactly(Tool.CURL);
    }
  }

  @Nested
  @DisplayName("requiresVerification")
  class RequiresVerification {

    @Test
    @DisplayName("should return true if any annotation requires verification")
    void shouldReturnTrueIfAnyAnnotationRequiresVerification() {
      final var annotation1 = createInstallPackages(false, "curl");
      final var annotation2 = createInstallPackages(true, "tcpdump");

      final var result = AnnotationMerger.requiresVerification(List.of(annotation1, annotation2));

      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("should return false if no annotation requires verification")
    void shouldReturnFalseIfNoAnnotationRequiresVerification() {
      final var annotation1 = createInstallPackages(false, "curl");
      final var annotation2 = createInstallPackages(false, "tcpdump");

      final var result = AnnotationMerger.requiresVerification(List.of(annotation1, annotation2));

      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("should return false for empty annotations list")
    void shouldReturnFalseForEmptyAnnotationsList() {
      final var result = AnnotationMerger.requiresVerification(List.of());

      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("should check InstallTools annotations")
    void shouldCheckInstallToolsAnnotations() {
      final var annotation1 = createInstallTools(false, Tool.CURL);
      final var annotation2 = createInstallTools(true, Tool.IPTABLES);

      final var result = AnnotationMerger.requiresVerification(List.of(annotation1, annotation2));

      assertThat(result).isTrue();
    }
  }

  // ==================== Helper Methods ====================

  private Annotation createInstallPackages(final String... packages) {
    return createInstallPackages(true, packages);
  }

  private Annotation createInstallPackages(final boolean verify, final String... packages) {
    return new InstallPackages() {
      @Override
      public Class<? extends Annotation> annotationType() {
        return InstallPackages.class;
      }

      @Override
      public String[] value() {
        return packages;
      }

      @Override
      public boolean verify() {
        return verify;
      }

      @Override
      public String target() {
        return "";
      }
    };
  }

  private Annotation createInstallTools(final Tool... tools) {
    return createInstallTools(true, tools);
  }

  private Annotation createInstallTools(final boolean verify, final Tool... tools) {
    return new InstallTools() {
      @Override
      public Class<? extends Annotation> annotationType() {
        return InstallTools.class;
      }

      @Override
      public Tool[] value() {
        return tools;
      }

      @Override
      public boolean verify() {
        return verify;
      }

      @Override
      public String target() {
        return "";
      }
    };
  }
}
