/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jdbc.testpack.l3.composers;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;

/**
 * Package-private utility that centralises opaque handle removal for JDBC L3 composers.
 *
 * <p>Dispatches on the runtime type of each handle and delegates to the appropriate domain
 * transport or accumulator. Only domains actually used in this module are represented.
 */
final class RuleRemover {

    private RuleRemover() {}

    /**
     * Removes a single handle, dispatching by type.
     *
     * @param container the container the rule was applied to
     * @param handle    opaque handle returned by a composer's {@code apply()}
     */
    static void remove(final GenericContainer<?> container, final Object handle) {
        if (handle instanceof com.macstab.chaos.connection.api.RuleHandle netRh) {
            new LibchaosTransport(LibchaosLib.NET).removeRules(container, netRh.owner());
        } else if (handle instanceof com.macstab.chaos.dns.api.RuleHandle dnsRh) {
            new LibchaosTransport(LibchaosLib.DNS).removeRules(container, dnsRh.owner());
        } else if (handle instanceof com.macstab.chaos.filesystem.api.RuleHandle ioRh) {
            new LibchaosTransport(LibchaosLib.IO).removeRules(container, ioRh.owner());
        } else if (handle instanceof String scenarioId) {
            JvmPlanAccumulator.instance().removeScenario(container, scenarioId);
        }
    }
}
