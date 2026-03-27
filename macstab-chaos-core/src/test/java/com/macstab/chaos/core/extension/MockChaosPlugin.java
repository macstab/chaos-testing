/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.macstab.chaos.core.annotation.ChaosTest;

/**
 * Mock plugin for testing ChaosTestingExtension.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class MockChaosPlugin implements ChaosPlugin<MockChaosPlugin.MockContainer> {

  /** Container annotation for repeatable MockContainer declarations. */
  @ChaosTest
  @Target(ElementType.TYPE)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface MockContainers {
    MockContainer[] value();
  }

  @ChaosTest
  @Repeatable(MockContainers.class)
  @Target(ElementType.TYPE)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface MockContainer {
    String image() default "alpine:latest";
    /** Port to expose. Use -1 (default) to skip port binding and wait strategy. */
    int port() default -1;
  }

  /** Marker interface — allows testing getBaseTypes() / getConnectionInfoByBaseType(). */
  public interface MockConnectionBase {
    String getHost();
    int getPort();
  }

  public static final class MockConnectionInfo implements MockConnectionBase {
    private final String host;
    private final int port;
    private final GenericContainer<?> container;

    public MockConnectionInfo(final String host, final int port, final GenericContainer<?> container) {
      this.host = host;
      this.port = port;
      this.container = container;
    }

    @Override
    public String getHost() {
      return host;
    }

    @Override
    public int getPort() {
      return port;
    }

    public GenericContainer<?> getContainer() {
      return container;
    }
  }

  @Override
  public Class<MockContainer> annotationType() {
    return MockContainer.class;
  }

  @Override
  public GenericContainer<?> createContainer(final MockContainer annotation) {
    final GenericContainer<?> container = new GenericContainer<>(
        DockerImageName.parse(annotation.image()));

    container.withCommand("sleep", "infinity");

    if (annotation.port() > 0) {
      container.withExposedPorts(annotation.port());
    }
    // Always use a command-based wait — Alpine runs no services, so
    // HostPortWaitStrategy would time out regardless of port config.
    container.waitingFor(Wait.forSuccessfulCommand("ls -la"));

    return container;
  }

  @Override
  public Object createConnectionInfo(final GenericContainer<?> container, final MockContainer annotation) {
    final int mappedPort = annotation.port() > 0 ? container.getMappedPort(annotation.port()) : -1;
    return new MockConnectionInfo(container.getHost(), mappedPort, container);
  }

  @Override
  public Set<Class<?>> supportedParameterTypes() {
    return Set.of(MockConnectionInfo.class, MockConnectionBase.class);
  }
}
