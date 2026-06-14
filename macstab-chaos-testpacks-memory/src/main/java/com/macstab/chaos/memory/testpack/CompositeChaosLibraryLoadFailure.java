/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates shared-library loading failure by injecting {@code EBADF} at 100% probability on
 * file-backed {@code mmap} calls. When the dynamic linker ({@code ld-linux}) calls {@code
 * mmap(MAP_PRIVATE, fd)} to map a shared library's ELF segments, a {@code EBADF} return causes
 * {@code dlopen()} to fail with {@code "invalid argument"} or a libc-internal error, propagating as
 * a {@code java.lang.UnsatisfiedLinkError} in the JVM or a {@code null} return from native {@code
 * dlopen()} in C/C++ applications.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code AdvancedMemoryChaos#failLibraryLoad(container, 1.0)} via libchaos-memory, which
 * installs a {@code mmap/file:ERRNO:EBADF@1.0} rule targeting file-backed mmap calls (those without
 * {@code MAP_ANONYMOUS}). In production library-load failures arise from filesystem permission
 * errors, missing or corrupt library files, ABI mismatches that cause the dynamic linker to reject
 * the file, or misconfigured {@code LD_LIBRARY_PATH} / {@code /etc/ld.so.conf} entries.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * At 100% toxicity every file-backed mmap call fails. {@code dlopen()} is completely broken —
 * plugin loaders, JNI libraries, Python extension modules, and any component that lazily loads
 * shared libraries at runtime will all fail. Services with static dependencies that are loaded at
 * start-up may fail to start entirely. Operator intervention (correcting the library configuration
 * or restarting with a patched image) is required.
 *
 * <h2>Industry references</h2>
 *
 * <p>{@code dlopen(3)} failure modes are documented in the Linux man-page for {@code dlopen(3)} and
 * in the ELF specification. The use of {@code mmap(MAP_PRIVATE)} for ELF segment loading is
 * described in the System V ABI and the GNU libc dynamic linker source ({@code elf/dl-load.c}). JNI
 * library loading failure is documented in the Java Native Interface Specification (section 2.4).
 * Plugin-loader error-path testing is a recurring theme in the Netflix Chaos Engineering corpus.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @CompositeChaosLibraryLoadFailure
 * class PluginLoaderResilienceTest {
 *
 *   @Test
 *   void pluginLoaderReturnsErrorOnLibraryLoadFailure(GenericContainer<?> app) {
 *     // Plugin load must fail gracefully — not crash the whole service
 *     final Response resp = client.loadPlugin("my-plugin");
 *     assertThat(resp.status()).isEqualTo(503);
 *     assertThat(resp.body()).contains("plugin unavailable");
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosLibraryLoadFailure.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.memory.testpack.composers.LibraryLoadFailureComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosLibraryLoadFailure {

  /**
   * Probability in {@code (0.0, 1.0]} that {@code EBADF} fires on each file-backed {@code mmap}
   * call ({@code dlopen()} / ELF segment loading). Defaults to {@code 1.0} (every library load
   * attempt fails). Lower values model intermittent plugin-load failures; use {@code 0.5} to
   * exercise retry logic without completely disabling the dynamic linker.
   */
  double toxicity() default 1.0;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-memory.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosLibraryLoadFailure[] value();
  }
}
