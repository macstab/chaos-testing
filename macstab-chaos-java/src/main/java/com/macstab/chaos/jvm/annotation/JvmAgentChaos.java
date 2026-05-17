/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts a test class into JVM-agent fault injection for every container created by chaos-core's
 * {@code ChaosTestingExtension}. The extension reflectively detects this annotation and calls
 * {@code JavaAgentTransport.prepare(container)} <em>before</em> {@code container.start()} — the
 * JVM-agent analogue of {@code @SyscallLevelChaos(LibchaosLib.X)} for the libchaos {@code .so}
 * libraries.
 *
 * <p><strong>Lifecycle contract.</strong> Like {@code LD_PRELOAD}, the {@code -javaagent} flag is
 * only honoured at JVM start — the agent jar must be copied into the container image overlay and
 * {@code JAVA_TOOL_OPTIONS} must be set before the container's main process boots. This annotation
 * is the user-visible signal that the lifecycle hand-off is in play; the extension drives {@code
 * JavaAgentTransport.prepare()} into the right window.
 *
 * <h2>Combines with the agent's own in-process attach</h2>
 *
 * <p>The chaos-testing-java-agent project ships {@code ChaosAgentExtension} (JUnit 5) and {@code
 * @ChaosTest} (Spring Boot 3 / 4) for the <em>in-process</em> path — when the test JVM <em>is</em>
 * the target JVM. {@code @JvmAgentChaos} is for the <em>container-side</em> path — when the test
 * drives a separate JDK 21+ Java container via testcontainers. Both annotations can co-exist on
 * the same test class; each handles the path it owns.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone   // creates a Redis container via the chaos-core plugin system
 * @JvmAgentChaos     // prepares the JVM agent on every created container, pre-start
 * class MyTest {
 *
 *   @Test
 *   void appHandlesJdbcAcquireFailure(RedisConnectionInfo info) {
 *     // agent is already loaded inside info.container() — push a plan via JavaAgentTransport
 *     // or apply scenarios through the agent's own ControlPlane API.
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Required dependency.</strong> {@code @JvmAgentChaos} only takes effect when {@code
 * macstab-chaos-java} (or a framework wrapper that pulls it transitively) is on the classpath.
 * Declaring the annotation without the dependency raises {@code ExtensionConfigurationException}
 * at test startup with a clear pointer to the missing build coordinate.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface JvmAgentChaos {}
