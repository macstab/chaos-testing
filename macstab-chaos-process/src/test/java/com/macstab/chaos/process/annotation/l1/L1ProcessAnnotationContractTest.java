/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.core.extension.OnMissingEnv;

@DisplayName("L1 Process annotation contract")
class L1ProcessAnnotationContractTest {

  static Stream<Arguments> allL1Annotations() throws Exception {
    List<Class<?>> result = new ArrayList<>();
    String rootPackage = "com.macstab.chaos.process.annotation.l1";
    String resourcePath = rootPackage.replace('.', '/');
    ClassLoader cl = L1ProcessAnnotationContractTest.class.getClassLoader();
    Enumeration<URL> resources = cl.getResources(resourcePath);
    while (resources.hasMoreElements()) {
      URL url = resources.nextElement();
      File dir = new File(url.toURI());
      scanDir(dir, rootPackage, result);
    }
    return result.stream()
        .filter(Class::isAnnotation)
        .filter(c -> c.getAnnotation(ChaosL1.class) != null)
        .sorted(Comparator.comparing(Class::getName))
        .map(Arguments::of);
  }

  static void scanDir(File dir, String pkg, List<Class<?>> out) {
    if (dir == null || !dir.exists()) return;
    for (File f : dir.listFiles()) {
      if (f.isDirectory()) {
        scanDir(f, pkg + "." + f.getName(), out);
      } else if (f.getName().endsWith(".class") && !f.getName().contains("$")) {
        String className = pkg + "." + f.getName().replace(".class", "");
        try {
          out.add(Class.forName(className));
        } catch (ClassNotFoundException ignored) {
        }
      }
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("allL1Annotations")
  @DisplayName("each @ChaosL1 annotation satisfies the universal contract")
  void annotationContract(Class<?> annotationClass) throws Exception {
    ChaosL1 l1 = annotationClass.getAnnotation(ChaosL1.class);
    assertThat(l1).as("@ChaosL1 on " + annotationClass.getSimpleName()).isNotNull();
    assertThat(l1.translator()).as("translator FQN").isNotBlank();

    Retention retention = annotationClass.getAnnotation(Retention.class);
    assertThat(retention).isNotNull();
    assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);

    Target target = annotationClass.getAnnotation(Target.class);
    assertThat(target).isNotNull();
    assertThat(Arrays.asList(target.value()))
        .containsExactlyInAnyOrder(ElementType.TYPE, ElementType.METHOD, ElementType.FIELD);

    java.lang.reflect.Method idMethod = annotationClass.getDeclaredMethod("id");
    assertThat(idMethod.getDefaultValue()).isEqualTo("");

    java.lang.reflect.Method onMissingEnvMethod = annotationClass.getDeclaredMethod("onMissingEnv");
    assertThat(onMissingEnvMethod.getDefaultValue()).isEqualTo(OnMissingEnv.ERROR);

    Class<?> repeatableInner =
        Arrays.stream(annotationClass.getDeclaredClasses())
            .filter(c -> c.getSimpleName().equals("Repeatable") && c.isAnnotation())
            .findFirst()
            .orElse(null);
    assertThat(repeatableInner).as("nested @interface Repeatable").isNotNull();

    Retention repRetention = repeatableInner.getAnnotation(Retention.class);
    assertThat(repRetention).isNotNull();
    assertThat(repRetention.value()).isEqualTo(RetentionPolicy.RUNTIME);
    Target repTarget = repeatableInner.getAnnotation(Target.class);
    assertThat(repTarget).isNotNull();
    assertThat(Arrays.asList(repTarget.value()))
        .containsExactlyInAnyOrder(ElementType.TYPE, ElementType.METHOD, ElementType.FIELD);

    Class<?> translatorClass = Class.forName(l1.translator());
    assertThat(translatorClass).isNotNull();

    assertThat(L1Translator.class.isAssignableFrom(translatorClass))
        .as(translatorClass.getName() + " must implement L1Translator")
        .isTrue();
  }

  @Test
  @DisplayName("scan finds the expected minimum number of L1 annotations")
  void foundExpectedCount() throws Exception {
    long count = allL1Annotations().count();
    assertThat(count).isGreaterThanOrEqualTo(108);
  }
}
