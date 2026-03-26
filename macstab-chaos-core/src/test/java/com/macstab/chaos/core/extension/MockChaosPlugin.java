/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.macstab.chaos.core.annotation.ChaosTest;

/**
 * Mock plugin for testing ChaosTestingExtension.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class MockChaosPlugin implements ChaosPlugin<MockChaosPlugin.MockContainer> {

  @ChaosTest
  @Target(ElementType.TYPE)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface MockContainer {
    String image() default "alpine:latest";
    int port() default 8080;
  }

  public static final class MockConnectionInfo {
    private final String host;
    private final int port;
    private final GenericContainer<?> container;

    public MockConnectionInfo(final String host, final int port, final GenericContainer<?> container) {
      this.host = host;
      this.port = port;
      this.container = container;
    }

    public String getHost() {
      return host;
    }

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
    container.withExposedPorts(annotation.port());
    
    return container;
  }

  @Override
  public Object createConnectionInfo(final GenericContainer<?> container, final MockContainer annotation) {
    return new MockConnectionInfo(
        container.getHost(),
        container.getMappedPort(annotation.port()),
        container);
  }

  @Override
  public Set<Class<?>> supportedParameterTypes() {
    return Set.of(MockConnectionInfo.class);
  }
}
