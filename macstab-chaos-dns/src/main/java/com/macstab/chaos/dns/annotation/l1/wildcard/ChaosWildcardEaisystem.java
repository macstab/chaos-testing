/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.annotation.l1.wildcard;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.dns.annotation.l1.DnsEaiBinding;
import com.macstab.chaos.dns.annotation.l1.DnsSelectorKind;
import com.macstab.chaos.dns.model.EaiErrno;

/**
 * L1 chaos primitive: inject {@code EAI_SYSTEM} on every libchaos-dns-intercepted
 * {@code every resolver} call inside the container.
 *
 * <p><strong>What this simulates:</strong> underlying syscall failure — consult errno for detail; typical of resolver-side fd exhaustion.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @ChaosWildcardEaisystem
 * class MyTest { ... }
 * }</pre>
 *
 * <p><strong>Scope:</strong> applies to every {@code every resolver} lookup in the container.
 * For per-host targeting use the imperative {@code AdvancedDnsChaos.failResolution} API.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.dns.model.DnsRule#eai
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.dns.annotation.l1.translators.DnsEaiTranslator")
@DnsEaiBinding(selectorKind = DnsSelectorKind.WILDCARD, errno = EaiErrno.EAI_SYSTEM)
public @interface ChaosWildcardEaisystem {

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the active backend cannot honour libchaos-dns */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
