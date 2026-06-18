/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.tracker;

import java.util.Map;
import java.util.Objects;

/**
 * Categorizes Redis commands into semantic groups (READ, WRITE, ADMIN, etc.).
 *
 * <p>Supports all standard Redis commands (as of Redis 7.x). Use {@link #categorize(String)} to
 * classify a command, or the shorthand predicates {@link #isRead(String)}, {@link
 * #isWrite(String)}.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * CommandCategory cat = CommandCategorizer.categorize("HGETALL");
 * assertThat(cat).isEqualTo(CommandCategory.READ);
 * assertThat(CommandCategorizer.isRead("GET")).isTrue();
 * assertThat(CommandCategorizer.isWrite("SET")).isTrue();
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
public final class CommandCategorizer {

  /** Redis command categories. */
  public enum CommandCategory {
    READ,
    WRITE,
    ADMIN,
    PUBSUB,
    SCRIPTING,
    TRANSACTION,
    STREAM,
    UNKNOWN
  }

  private static final Map<String, CommandCategory> COMMAND_MAP = buildCommandMap();

  private CommandCategorizer() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Categorizes a Redis command.
   *
   * @param command Redis command name (e.g., "GET", "SET") — must not be null
   * @return category (never null, returns {@link CommandCategory#UNKNOWN} if unrecognized)
   * @throws NullPointerException if command is null
   */
  public static CommandCategory categorize(final String command) {
    Objects.requireNonNull(command, "command");
    return COMMAND_MAP.getOrDefault(command.toUpperCase(), CommandCategory.UNKNOWN);
  }

  /**
   * Checks if command is a read operation.
   *
   * @param command Redis command name — must not be null
   * @return true if read command
   */
  public static boolean isRead(final String command) {
    return categorize(command) == CommandCategory.READ;
  }

  /**
   * Checks if command is a write operation.
   *
   * @param command Redis command name — must not be null
   * @return true if write command
   */
  public static boolean isWrite(final String command) {
    return categorize(command) == CommandCategory.WRITE;
  }

  /** Builds the command-to-category map (called once at class load). */
  @SuppressWarnings("java:S1192") // String literals OK in static map
  private static Map<String, CommandCategory> buildCommandMap() {
    return Map.ofEntries(
        // READ commands
        Map.entry("GET", CommandCategory.READ),
        Map.entry("MGET", CommandCategory.READ),
        Map.entry("HGET", CommandCategory.READ),
        Map.entry("HGETALL", CommandCategory.READ),
        Map.entry("HMGET", CommandCategory.READ),
        Map.entry("HKEYS", CommandCategory.READ),
        Map.entry("HVALS", CommandCategory.READ),
        Map.entry("HLEN", CommandCategory.READ),
        Map.entry("HEXISTS", CommandCategory.READ),
        Map.entry("LRANGE", CommandCategory.READ),
        Map.entry("LINDEX", CommandCategory.READ),
        Map.entry("LLEN", CommandCategory.READ),
        Map.entry("LPOS", CommandCategory.READ),
        Map.entry("SMEMBERS", CommandCategory.READ),
        Map.entry("SISMEMBER", CommandCategory.READ),
        Map.entry("SCARD", CommandCategory.READ),
        Map.entry("SUNION", CommandCategory.READ),
        Map.entry("SINTER", CommandCategory.READ),
        Map.entry("SDIFF", CommandCategory.READ),
        Map.entry("ZRANGE", CommandCategory.READ),
        Map.entry("ZRANGEBYSCORE", CommandCategory.READ),
        Map.entry("ZRANGEBYLEX", CommandCategory.READ),
        Map.entry("ZREVRANGE", CommandCategory.READ),
        Map.entry("ZREVRANGEBYSCORE", CommandCategory.READ),
        Map.entry("ZRANK", CommandCategory.READ),
        Map.entry("ZREVRANK", CommandCategory.READ),
        Map.entry("ZSCORE", CommandCategory.READ),
        Map.entry("ZMSCORE", CommandCategory.READ),
        Map.entry("ZCARD", CommandCategory.READ),
        Map.entry("ZCOUNT", CommandCategory.READ),
        Map.entry("ZLEXCOUNT", CommandCategory.READ),
        Map.entry("ZUNION", CommandCategory.READ),
        Map.entry("ZINTER", CommandCategory.READ),
        Map.entry("ZDIFF", CommandCategory.READ),
        Map.entry("TYPE", CommandCategory.READ),
        Map.entry("TTL", CommandCategory.READ),
        Map.entry("PTTL", CommandCategory.READ),
        Map.entry("EXISTS", CommandCategory.READ),
        Map.entry("STRLEN", CommandCategory.READ),
        Map.entry("GETRANGE", CommandCategory.READ),
        Map.entry("GETBIT", CommandCategory.READ),
        Map.entry("BITCOUNT", CommandCategory.READ),
        Map.entry("BITPOS", CommandCategory.READ),
        Map.entry("KEYS", CommandCategory.READ),
        Map.entry("SCAN", CommandCategory.READ),
        Map.entry("HSCAN", CommandCategory.READ),
        Map.entry("SSCAN", CommandCategory.READ),
        Map.entry("ZSCAN", CommandCategory.READ),
        Map.entry("OBJECT", CommandCategory.READ),
        Map.entry("DUMP", CommandCategory.READ),
        Map.entry("GEORADIUS", CommandCategory.READ),
        Map.entry("GEOPOS", CommandCategory.READ),
        Map.entry("GEODIST", CommandCategory.READ),
        Map.entry("GEOSEARCH", CommandCategory.READ),
        Map.entry("LMPOP", CommandCategory.READ),
        Map.entry("ZMPOP", CommandCategory.READ),
        // WRITE commands
        Map.entry("SET", CommandCategory.WRITE),
        Map.entry("MSET", CommandCategory.WRITE),
        Map.entry("HSET", CommandCategory.WRITE),
        Map.entry("HMSET", CommandCategory.WRITE),
        Map.entry("HSETNX", CommandCategory.WRITE),
        Map.entry("HDEL", CommandCategory.WRITE),
        Map.entry("LPUSH", CommandCategory.WRITE),
        Map.entry("RPUSH", CommandCategory.WRITE),
        Map.entry("LPOP", CommandCategory.WRITE),
        Map.entry("RPOP", CommandCategory.WRITE),
        Map.entry("LINSERT", CommandCategory.WRITE),
        Map.entry("LSET", CommandCategory.WRITE),
        Map.entry("LREM", CommandCategory.WRITE),
        Map.entry("LTRIM", CommandCategory.WRITE),
        Map.entry("SADD", CommandCategory.WRITE),
        Map.entry("SREM", CommandCategory.WRITE),
        Map.entry("SPOP", CommandCategory.WRITE),
        Map.entry("SMOVE", CommandCategory.WRITE),
        Map.entry("SUNIONSTORE", CommandCategory.WRITE),
        Map.entry("SINTERSTORE", CommandCategory.WRITE),
        Map.entry("SDIFFSTORE", CommandCategory.WRITE),
        Map.entry("ZADD", CommandCategory.WRITE),
        Map.entry("ZREM", CommandCategory.WRITE),
        Map.entry("ZINCRBY", CommandCategory.WRITE),
        Map.entry("ZUNIONSTORE", CommandCategory.WRITE),
        Map.entry("ZINTERSTORE", CommandCategory.WRITE),
        Map.entry("ZDIFFSTORE", CommandCategory.WRITE),
        Map.entry("ZPOPMIN", CommandCategory.WRITE),
        Map.entry("ZPOPMAX", CommandCategory.WRITE),
        Map.entry("INCR", CommandCategory.WRITE),
        Map.entry("INCRBY", CommandCategory.WRITE),
        Map.entry("INCRBYFLOAT", CommandCategory.WRITE),
        Map.entry("DECR", CommandCategory.WRITE),
        Map.entry("DECRBY", CommandCategory.WRITE),
        Map.entry("APPEND", CommandCategory.WRITE),
        Map.entry("SETRANGE", CommandCategory.WRITE),
        Map.entry("SETBIT", CommandCategory.WRITE),
        Map.entry("GETSET", CommandCategory.WRITE),
        Map.entry("SETNX", CommandCategory.WRITE),
        Map.entry("SETEX", CommandCategory.WRITE),
        Map.entry("PSETEX", CommandCategory.WRITE),
        Map.entry("MSETNX", CommandCategory.WRITE),
        Map.entry("GETDEL", CommandCategory.WRITE),
        Map.entry("GETEX", CommandCategory.WRITE),
        Map.entry("DEL", CommandCategory.WRITE),
        Map.entry("UNLINK", CommandCategory.WRITE),
        Map.entry("PERSIST", CommandCategory.WRITE),
        Map.entry("EXPIRE", CommandCategory.WRITE),
        Map.entry("PEXPIRE", CommandCategory.WRITE),
        Map.entry("EXPIREAT", CommandCategory.WRITE),
        Map.entry("PEXPIREAT", CommandCategory.WRITE),
        Map.entry("RENAME", CommandCategory.WRITE),
        Map.entry("RENAMENX", CommandCategory.WRITE),
        Map.entry("MOVE", CommandCategory.WRITE),
        Map.entry("RESTORE", CommandCategory.WRITE),
        Map.entry("SORT", CommandCategory.WRITE),
        Map.entry("COPY", CommandCategory.WRITE),
        Map.entry("LMOVE", CommandCategory.WRITE),
        Map.entry("BLMOVE", CommandCategory.WRITE),
        Map.entry("GEOADD", CommandCategory.WRITE),
        // ADMIN commands
        Map.entry("PING", CommandCategory.ADMIN),
        Map.entry("AUTH", CommandCategory.ADMIN),
        Map.entry("SELECT", CommandCategory.ADMIN),
        Map.entry("FLUSHDB", CommandCategory.ADMIN),
        Map.entry("FLUSHALL", CommandCategory.ADMIN),
        Map.entry("DBSIZE", CommandCategory.ADMIN),
        Map.entry("INFO", CommandCategory.ADMIN),
        Map.entry("CONFIG", CommandCategory.ADMIN),
        Map.entry("RESET", CommandCategory.ADMIN),
        Map.entry("QUIT", CommandCategory.ADMIN),
        Map.entry("SAVE", CommandCategory.ADMIN),
        Map.entry("BGSAVE", CommandCategory.ADMIN),
        Map.entry("BGREWRITEAOF", CommandCategory.ADMIN),
        Map.entry("LASTSAVE", CommandCategory.ADMIN),
        Map.entry("SLOWLOG", CommandCategory.ADMIN),
        Map.entry("CLIENT", CommandCategory.ADMIN),
        Map.entry("COMMAND", CommandCategory.ADMIN),
        Map.entry("DEBUG", CommandCategory.ADMIN),
        Map.entry("WAIT", CommandCategory.ADMIN),
        Map.entry("MEMORY", CommandCategory.ADMIN),
        Map.entry("MODULE", CommandCategory.ADMIN),
        Map.entry("LOLWUT", CommandCategory.ADMIN),
        Map.entry("FAILOVER", CommandCategory.ADMIN),
        Map.entry("REPLICAOF", CommandCategory.ADMIN),
        Map.entry("SLAVEOF", CommandCategory.ADMIN),
        Map.entry("SHUTDOWN", CommandCategory.ADMIN),
        Map.entry("SWAPDB", CommandCategory.ADMIN),
        Map.entry("CLUSTER", CommandCategory.ADMIN),
        Map.entry("LATENCY", CommandCategory.ADMIN),
        Map.entry("ACL", CommandCategory.ADMIN),
        Map.entry("HELLO", CommandCategory.ADMIN),
        Map.entry("TIME", CommandCategory.ADMIN),
        Map.entry("ECHO", CommandCategory.ADMIN),
        Map.entry("SRANDMEMBER", CommandCategory.ADMIN),
        Map.entry("RANDOMKEY", CommandCategory.ADMIN),
        // PUBSUB commands
        Map.entry("PUBLISH", CommandCategory.PUBSUB),
        Map.entry("SUBSCRIBE", CommandCategory.PUBSUB),
        Map.entry("UNSUBSCRIBE", CommandCategory.PUBSUB),
        Map.entry("PSUBSCRIBE", CommandCategory.PUBSUB),
        Map.entry("PUNSUBSCRIBE", CommandCategory.PUBSUB),
        Map.entry("PUBSUB", CommandCategory.PUBSUB),
        Map.entry("SSUBSCRIBE", CommandCategory.PUBSUB),
        Map.entry("SUNSUBSCRIBE", CommandCategory.PUBSUB),
        // SCRIPTING commands
        Map.entry("EVAL", CommandCategory.SCRIPTING),
        Map.entry("EVALSHA", CommandCategory.SCRIPTING),
        Map.entry("EVALRO", CommandCategory.SCRIPTING),
        Map.entry("EVALSHARO", CommandCategory.SCRIPTING),
        Map.entry("SCRIPT", CommandCategory.SCRIPTING),
        Map.entry("FCALL", CommandCategory.SCRIPTING),
        Map.entry("FUNCTION", CommandCategory.SCRIPTING),
        Map.entry("WAITAOF", CommandCategory.SCRIPTING),
        // TRANSACTION commands
        Map.entry("MULTI", CommandCategory.TRANSACTION),
        Map.entry("EXEC", CommandCategory.TRANSACTION),
        Map.entry("DISCARD", CommandCategory.TRANSACTION),
        Map.entry("WATCH", CommandCategory.TRANSACTION),
        Map.entry("UNWATCH", CommandCategory.TRANSACTION),
        // STREAM commands
        Map.entry("XADD", CommandCategory.STREAM),
        Map.entry("XREAD", CommandCategory.STREAM),
        Map.entry("XREADGROUP", CommandCategory.STREAM),
        Map.entry("XACK", CommandCategory.STREAM),
        Map.entry("XLEN", CommandCategory.STREAM),
        Map.entry("XRANGE", CommandCategory.STREAM),
        Map.entry("XREVRANGE", CommandCategory.STREAM),
        Map.entry("XINFO", CommandCategory.STREAM),
        Map.entry("XDEL", CommandCategory.STREAM),
        Map.entry("XTRIM", CommandCategory.STREAM),
        Map.entry("XGROUP", CommandCategory.STREAM),
        Map.entry("XCLAIM", CommandCategory.STREAM),
        Map.entry("XPENDING", CommandCategory.STREAM),
        Map.entry("XAUTOCLAIM", CommandCategory.STREAM));
  }
}
