/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.macstab.chaos.core.annotation.InstallPackages;
import com.macstab.chaos.core.annotation.InstallTools;
import com.macstab.chaos.core.platform.Tool;

/**
 * Merges and deduplicates annotations.
 *
 * <p>Combines CLASS-level and FIELD-level annotations, deduplicating packages and tools.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class AnnotationMerger {

  private AnnotationMerger() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Merge and deduplicate InstallPackages annotations.
   *
   * @param annotations list of annotations
   * @return deduplicated package list
   */
  public static List<String> mergePackages(final List<Annotation> annotations) {
    Objects.requireNonNull(annotations, "annotations must not be null");

    final Set<String> packages = new LinkedHashSet<>();

    for (final Annotation annotation : annotations) {
      if (annotation instanceof InstallPackages installPackages) {
        for (final String pkg : installPackages.value()) {
          packages.add(pkg);
        }
      }
    }

    return new ArrayList<>(packages);
  }

  /**
   * Merge and deduplicate InstallTools annotations.
   *
   * @param annotations list of annotations
   * @return deduplicated tool list
   */
  public static List<Tool> mergeTools(final List<Annotation> annotations) {
    Objects.requireNonNull(annotations, "annotations must not be null");

    final Set<Tool> tools = new LinkedHashSet<>();

    for (final Annotation annotation : annotations) {
      if (annotation instanceof InstallTools installTools) {
        for (final Tool tool : installTools.value()) {
          tools.add(tool);
        }
      }
    }

    return new ArrayList<>(tools);
  }

  /**
   * Check if any annotation requires verification.
   *
   * <p>Returns true if ANY annotation has {@code verify = true}.
   *
   * @param annotations list of annotations
   * @return true if verification required
   */
  public static boolean requiresVerification(final List<Annotation> annotations) {
    Objects.requireNonNull(annotations, "annotations must not be null");

    for (final Annotation annotation : annotations) {
      if (annotation instanceof InstallPackages installPackages) {
        if (installPackages.verify()) {
          return true;
        }
      } else if (annotation instanceof InstallTools installTools) {
        if (installTools.verify()) {
          return true;
        }
      }
    }

    return false;
  }
}
