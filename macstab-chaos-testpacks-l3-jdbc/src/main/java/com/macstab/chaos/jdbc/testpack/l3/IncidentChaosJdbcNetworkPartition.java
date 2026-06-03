/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jdbc.testpack.l3;

import java.lang.annotation.*;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 * <p>Simulates a network partition during active two-phase commit (2PC): POLL-level timeouts model
 * the partition itself while EPIPE errors on SEND simulate broken pipes from severed connections,
 * leaving in-doubt transactions in an indeterminate state with potential split-brain risk.
 *
 * <h2>Composed of</h2>
 * <ul>
 *   <li>Connection: timeout on wildcard endpoint with duration {@code timeoutMs} ms at
 *       {@code toxicity} — POLL timeout simulates a network partition cutting the TCP path
 *   <li>Connection: SEND → EPIPE at {@code toxicity} — broken-pipe errors on active send
 *       operations model severed connections inside open transactions
 *   <li>JVM: {@code injectException("java.sql.SQLException", "transaction aborted — network
 *       partition")} on classes matching {@code classPattern} at METHOD_ENTER — surfaces partition
 *       state to the application layer
 * </ul>
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>Critical</strong><br>In-doubt 2PC transactions block row locks; applications
 * cannot commit or roll back; split-brain risk grows with partition duration; manual DBA
 * intervention may be required to resolve in-doubt transactions.
 *
 * <h2>Industry references</h2>
 * <p>Network partition during active 2PC is described in the distributed systems literature
 * (Gray &amp; Lamport "Consensus on Transaction Commit"), PostgreSQL documentation §"Two-Phase
 * Transactions", and post-mortems from microservice architectures using XA transactions across
 * multiple databases.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET})
 * @IncidentChaosJdbcNetworkPartition(toxicity = 0.9, timeoutMs = 3000L)
 * class JdbcNetworkPartitionTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosJdbcNetworkPartition.List.class)
@ChaosL3(composer = "com.macstab.chaos.jdbc.testpack.l3.composers.JdbcNetworkPartitionComposer", severity = Severity.CRITICAL)
public @interface IncidentChaosJdbcNetworkPartition {

    /** Container filter id; empty string matches all containers. */
    String id() default "";

    /** Fraction of POLL/SEND syscalls subjected to the partition fault (0.0–1.0). */
    double toxicity() default 0.8;

    /** Milliseconds for the POLL timeout, modelling the partition window. */
    long timeoutMs() default 5000L;

    /** Class name prefix used to match JDBC client methods for exception injection. */
    String classPattern() default "jdbc";

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        IncidentChaosJdbcNetworkPartition[] value();
    }
}
