/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension.internal;

import com.macstab.chaos.redis.extension.RedisContainerExtension.RedisConnectionInfo;
import com.macstab.chaos.redis.extension.RedisContainerExtension.Store;

/**
 * Sealed result type for single-instance startup operations.
 *
 * <p><strong>Purpose:</strong> Type-safe representation of startup outcome. Replaces the mutable
 * {@code InstanceStartupResult} inner class with an immutable sealed hierarchy.
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * StartupResult result = startSingleInstance(annotation);
 * switch (result) {
 *   case StartupResult.Success s -> handleSuccess(s.instanceId(), s.connectionInfo(), s.store());
 *   case StartupResult.Failure f -> handleFailure(f.instanceId(), f.errorMessage(), f.error());
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
public sealed interface StartupResult permits StartupResult.Success, StartupResult.Failure {

  /**
   * Returns the instance ID associated with this result.
   *
   * @return instance ID (never null)
   */
  String instanceId();

  /**
   * Successful startup result.
   *
   * @param instanceId instance ID from {@code @RedisStandalone(id = "...")}
   * @param connectionInfo connection details for the started instance
   * @param store store holding the container reference for lifecycle management
   */
  record Success(String instanceId, RedisConnectionInfo connectionInfo, Store store)
      implements StartupResult {}

  /**
   * Failed startup result.
   *
   * @param instanceId instance ID from {@code @RedisStandalone(id = "...")}
   * @param errorMessage human-readable error description
   * @param error original exception (may be null if not exception-based)
   */
  record Failure(String instanceId, String errorMessage, Exception error)
      implements StartupResult {}
}
