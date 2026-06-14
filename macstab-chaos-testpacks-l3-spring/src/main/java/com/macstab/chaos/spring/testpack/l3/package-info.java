/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L3 incident annotations for Spring Framework production failure scenarios.
 *
 * <p>Each annotation composes multiple chaos domains (network, DNS, JVM) to reproduce named Spring
 * production-incident patterns: connection pool exhaustion, reactor starvation, OSIV connection
 * drain, and cascading config refresh.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.spring.testpack.l3;
