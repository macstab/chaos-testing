/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.model;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Effect to apply when a libchaos-dns rule matches.
 *
 * <p>Sealed algebraic data type covering the eight effect kinds in the libchaos-dns rule grammar:
 *
 * <ul>
 *   <li>{@link EaiFault} — fail the resolver call with a specific {@link EaiErrno}
 *   <li>{@link Latency} — delay the call by a {@link Duration} before delegating to libc
 *   <li>{@link Rewrite} — rewrite the queried host name (forward) or the returned host text
 *       (reverse) before/after libc
 *   <li>{@link Service} — rewrite the service token
 *   <li>{@link Override} — synthesize a fixed list of {@link InetAddress} answers (forward only)
 *   <li>{@link FilterFamily} — drop result nodes whose family doesn't match (forward only)
 *   <li>{@link Limit} — keep only the first N result nodes (forward only)
 *   <li>{@link Shuffle} — Fisher-Yates re-link of the result list (forward only, singleton)
 * </ul>
 *
 * <p>Selector-kind compatibility is enforced at {@link DnsRule} construction time, not here.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/DNS.md">libchaos-dns
 *     rule grammar</a>
 */
public sealed interface Effect
    permits Effect.EaiFault,
        Effect.Latency,
        Effect.Rewrite,
        Effect.Service,
        Effect.OverrideAnswer,
        Effect.FilterFamily,
        Effect.Limit,
        Effect.Shuffle {

  /**
   * Renders this effect as the libchaos-dns rule body fragment (the {@code <effect>:<value>} pair).
   *
   * @return non-null wire form, e.g. {@code "EAI_FAIL"} or {@code "LATENCY:200"}
   */
  String wireForm();

  /**
   * @return {@code true} when this effect is only meaningful on a forward (getaddrinfo) selector
   */
  boolean isForwardOnly();

  // ==================== Static factories ====================

  /**
   * @param errno EAI code to inject
   * @return EAI-fault effect (works on both forward and reverse selectors)
   */
  static Effect eai(final EaiErrno errno) {
    return new EaiFault(errno);
  }

  /**
   * @param delay non-negative delay
   * @return latency effect with probability {@code 1.0} (works on both forward and reverse selectors)
   */
  static Effect latency(final Duration delay) {
    return new Latency(delay, 1.0);
  }

  /**
   * @param delay non-negative delay
   * @param probability probability in {@code (0.0, 1.0]} that the delay fires
   * @return latency effect (works on both forward and reverse selectors)
   */
  static Effect latency(final Duration delay, final double probability) {
    return new Latency(delay, probability);
  }

  /**
   * @param to replacement host text
   * @return host-rewrite effect (works on both forward and reverse selectors)
   */
  static Effect rewrite(final String to) {
    return new Rewrite(to);
  }

  /**
   * @param to replacement service text
   * @return service-rewrite effect (works on both forward and reverse selectors)
   */
  static Effect service(final String to) {
    return new Service(to);
  }

  /**
   * @param answers non-empty list of synthetic answer addresses
   * @return override effect (forward only)
   */
  static Effect override(final List<InetAddress> answers) {
    return new OverrideAnswer(answers);
  }

  /**
   * @param family address family to retain
   * @return filter-family effect (forward only)
   */
  static Effect filterFamily(final AddressFamily family) {
    return new FilterFamily(family);
  }

  /**
   * @param max maximum number of result nodes to retain ({@code >= 0})
   * @return limit effect (forward only)
   */
  static Effect limit(final int max) {
    return new Limit(max);
  }

  /**
   * @return shuffle effect (forward only, singleton)
   */
  static Effect shuffle() {
    return Shuffle.INSTANCE;
  }

  // ==================== Variants ====================

  /** Inject an {@link EaiErrno} on the matched resolver call. */
  record EaiFault(EaiErrno errno) implements Effect {
    public EaiFault {
      Objects.requireNonNull(errno, "errno must not be null");
    }

    @Override
    public String wireForm() {
      return errno.wireForm();
    }

    @Override
    public boolean isForwardOnly() {
      return false;
    }
  }

  /**
   * Delay the matched resolver call by {@code delay}, gated by {@code probability}. Rendered as
   * {@code LATENCY:<ms>[@<probability>]} — the {@code @<probability>} suffix is omitted when {@code
   * probability == 1.0}.
   */
  record Latency(Duration delay, double probability) implements Effect {
    public Latency {
      Objects.requireNonNull(delay, "delay must not be null");
      if (delay.isNegative()) {
        throw new IllegalArgumentException("delay must not be negative: " + delay);
      }
      if (Double.isNaN(probability) || probability <= 0.0 || probability > 1.0) {
        throw new IllegalArgumentException(
            "probability must be in (0.0, 1.0], got: " + probability);
      }
    }

    @Override
    public String wireForm() {
      final String body = "LATENCY:" + delay.toMillis();
      return probability == 1.0 ? body : body + "@" + probability;
    }

    @Override
    public boolean isForwardOnly() {
      return false;
    }
  }

  /**
   * Rewrite the host text. On a forward selector this rewrites the queried name before resolution;
   * on a reverse selector this rewrites the returned host buffer after a successful libc call.
   */
  record Rewrite(String to) implements Effect {
    public Rewrite {
      Objects.requireNonNull(to, "to must not be null");
      if (to.isBlank()) {
        throw new IllegalArgumentException("to must not be blank");
      }
      requireNoNewlineOrColon(to, "to");
    }

    @Override
    public String wireForm() {
      return "REWRITE:" + to;
    }

    @Override
    public boolean isForwardOnly() {
      return false;
    }
  }

  /** Rewrite the service text — forward (pre-resolution) or reverse (post-success buffer). */
  record Service(String to) implements Effect {
    public Service {
      Objects.requireNonNull(to, "to must not be null");
      if (to.isBlank()) {
        throw new IllegalArgumentException("to must not be blank");
      }
      requireNoNewlineOrColon(to, "to");
    }

    @Override
    public String wireForm() {
      return "SERVICE:" + to;
    }

    @Override
    public boolean isForwardOnly() {
      return false;
    }
  }

  /**
   * Replace the real resolution with a fabricated answer list. The library invokes the real
   * resolver with each literal via {@code AI_NUMERICHOST} so subsequent transforms (FILTER_FAMILY,
   * LIMIT) can free the synthetic list correctly.
   */
  record OverrideAnswer(List<InetAddress> answers) implements Effect {
    public OverrideAnswer {
      Objects.requireNonNull(answers, "answers must not be null");
      if (answers.isEmpty()) {
        throw new IllegalArgumentException("answers must not be empty");
      }
      for (int i = 0; i < answers.size(); i++) {
        Objects.requireNonNull(answers.get(i), "answers element " + i + " must not be null");
      }
      // Defensive copy — guards against post-construction mutation
      answers = List.copyOf(answers);
    }

    @Override
    public String wireForm() {
      return "OVERRIDE:"
          + answers.stream().map(OverrideAnswer::renderAddress).collect(Collectors.joining(","));
    }

    @Override
    public boolean isForwardOnly() {
      return true;
    }

    private static String renderAddress(final InetAddress address) {
      // IPv6 literals must be bracketed in OVERRIDE to keep the comma-separator unambiguous.
      // {@link Inet6Address#getHostAddress()} returns the expanded form
      // ({@code 0:0:0:0:0:0:0:1}); we compress to the canonical RFC 5952 form ({@code ::1}) so the
      // wire matches the libchaos-dns documentation examples and stays human-readable.
      return address instanceof Inet6Address v6
          ? "[" + compressIpv6(v6) + "]"
          : address.getHostAddress();
    }

    /**
     * Canonical RFC 5952 short-form for an IPv6 address — collapses the longest run of zero groups
     * (length ≥ 2) into {@code ::}. The JDK's {@code Inet6Address.getHostAddress()} returns the
     * expanded form so we do the compression ourselves.
     */
    private static String compressIpv6(final Inet6Address address) {
      final byte[] bytes = address.getAddress();
      final int[] groups = new int[8];
      for (int i = 0; i < 8; i++) {
        groups[i] = ((bytes[i * 2] & 0xff) << 8) | (bytes[i * 2 + 1] & 0xff);
      }
      // Locate the longest run of zeros (must be length >= 2 per RFC 5952)
      int bestStart = -1;
      int bestLen = 1;
      int i = 0;
      while (i < 8) {
        if (groups[i] != 0) {
          i++;
          continue;
        }
        int j = i;
        while (j < 8 && groups[j] == 0) {
          j++;
        }
        final int len = j - i;
        if (len > bestLen) {
          bestLen = len;
          bestStart = i;
        }
        i = j;
      }
      final StringBuilder sb = new StringBuilder();
      i = 0;
      while (i < 8) {
        if (i == bestStart) {
          sb.append("::");
          i += bestLen;
          continue;
        }
        if (i > 0 && (sb.length() == 0 || sb.charAt(sb.length() - 1) != ':')) {
          sb.append(':');
        }
        sb.append(Integer.toHexString(groups[i]));
        i++;
      }
      return sb.toString();
    }
  }

  /** Retain only result nodes whose family matches. Forward only. */
  record FilterFamily(AddressFamily family) implements Effect {
    public FilterFamily {
      Objects.requireNonNull(family, "family must not be null");
    }

    @Override
    public String wireForm() {
      return "FILTER_FAMILY:" + family.wireForm();
    }

    @Override
    public boolean isForwardOnly() {
      return true;
    }
  }

  /** Keep only the first {@code max} result nodes. Forward only. */
  record Limit(int max) implements Effect {
    public Limit {
      if (max < 0) {
        throw new IllegalArgumentException("max must not be negative: " + max);
      }
    }

    @Override
    public String wireForm() {
      return "LIMIT:" + max;
    }

    @Override
    public boolean isForwardOnly() {
      return true;
    }
  }

  /** Fisher-Yates re-link of the result list. Forward only, singleton. */
  enum Shuffle implements Effect {
    /** The single shuffle-effect instance. */
    INSTANCE;

    @Override
    public String wireForm() {
      return "SHUFFLE";
    }

    @Override
    public boolean isForwardOnly() {
      return true;
    }
  }

  // ==================== Validation helpers ====================

  private static void requireNoNewlineOrColon(final String value, final String fieldName) {
    if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
      throw new IllegalArgumentException(
          fieldName + " must not contain newline characters (config-file injection guard)");
    }
    if (value.indexOf(':') >= 0) {
      throw new IllegalArgumentException(
          fieldName + " must not contain ':' (config-file field-separator collision): " + value);
    }
  }
}
