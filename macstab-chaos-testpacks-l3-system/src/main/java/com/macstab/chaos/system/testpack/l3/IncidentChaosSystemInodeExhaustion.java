/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.system.testpack.l3;

import java.lang.annotation.*;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 * <p>Simulates inode exhaustion: disk shows 40% used, {@code df -h} looks green, but every
 * {@code open()}, {@code mkdir()}, and temporary-file creation fails with {@code ENOSPC}.
 * All inode slots are consumed. Operations staff stare at {@code df} output and see nothing wrong.
 *
 * <h2>Composed of</h2>
 * <ul>
 *   <li>Filesystem: {@code OPEN ENOSPC} at {@code toxicity} — inode exhaustion manifests as
 *       {@code ENOSPC} on every file-open attempt
 * </ul>
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>Critical</strong><br>All log writes, temporary files, and socket
 * creation fail. {@code df -h} shows plenty of space. Root cause is only visible via
 * {@code df -i} (inode usage).
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @AppContainer
 * @IncidentChaosSystemInodeExhaustion(toxicity = 0.9)
 * class InodeExhaustionTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosSystemInodeExhaustion.List.class)
@ChaosL3(composer = "com.macstab.chaos.system.testpack.l3.composers.SystemInodeExhaustionComposer", severity = Severity.CRITICAL)
public @interface IncidentChaosSystemInodeExhaustion {

    /** Container filter id; empty string matches all containers. */
    String id() default "";

    /** Fraction of {@code open()} calls that fail with ENOSPC (0.0–1.0). */
    double toxicity() default 0.9;

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        IncidentChaosSystemInodeExhaustion[] value();
    }
}
