/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.springboot.testpack.l3.composers;

import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class RuleRemover {

    private RuleRemover() {}

    static void removeAll(final GenericContainer<?> container, final List<Object> handles) {
        for (final Object h : handles) {
            try {
                if (h instanceof com.macstab.chaos.connection.api.RuleHandle rh) {
                    new LibchaosTransport(LibchaosLib.NET).removeRules(container, rh.owner());
                } else if (h instanceof com.macstab.chaos.dns.api.RuleHandle rh) {
                    new LibchaosTransport(LibchaosLib.DNS).removeRules(container, rh.owner());
                } else if (h instanceof com.macstab.chaos.memory.api.RuleHandle rh) {
                    new LibchaosTransport(LibchaosLib.MEMORY).removeRules(container, rh.owner());
                } else if (h instanceof com.macstab.chaos.process.api.RuleHandle rh) {
                    new LibchaosTransport(LibchaosLib.PROCESS).removeRules(container, rh.owner());
                } else if (h instanceof String scenarioId) {
                    JvmPlanAccumulator.instance().removeScenario(container, scenarioId);
                }
            } catch (final Exception e) {
                log.warn("RuleRemover: failed to remove handle {}", h, e);
            }
        }
    }
}
