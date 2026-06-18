/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.redis.util.tracker.CommandCategorizer.CommandCategory;

/** Comprehensive unit tests for {@link CommandCategorizer}. */
@DisplayName("CommandCategorizer")
class CommandCategorizerTest {

  @Nested
  @DisplayName("categorize() - READ commands")
  class ReadCommands {

    @Test
    @DisplayName("Should categorize GET as READ")
    void shouldCategorizeGet() {
      assertThat(CommandCategorizer.categorize("GET")).isEqualTo(CommandCategory.READ);
    }

    @Test
    @DisplayName("Should categorize HGETALL as READ")
    void shouldCategorizeHgetall() {
      assertThat(CommandCategorizer.categorize("HGETALL")).isEqualTo(CommandCategory.READ);
    }

    @Test
    @DisplayName("Should categorize LRANGE as READ")
    void shouldCategorizeLrange() {
      assertThat(CommandCategorizer.categorize("LRANGE")).isEqualTo(CommandCategory.READ);
    }

    @Test
    @DisplayName("Should categorize ZRANGE as READ")
    void shouldCategorizeZrange() {
      assertThat(CommandCategorizer.categorize("ZRANGE")).isEqualTo(CommandCategory.READ);
    }

    @Test
    @DisplayName("Should categorize SMEMBERS as READ")
    void shouldCategorizeSmembers() {
      assertThat(CommandCategorizer.categorize("SMEMBERS")).isEqualTo(CommandCategory.READ);
    }

    @Test
    @DisplayName("Should categorize KEYS as READ")
    void shouldCategorizeKeys() {
      assertThat(CommandCategorizer.categorize("KEYS")).isEqualTo(CommandCategory.READ);
    }

    @Test
    @DisplayName("Should categorize SCAN as READ")
    void shouldCategorizeScan() {
      assertThat(CommandCategorizer.categorize("SCAN")).isEqualTo(CommandCategory.READ);
    }

    @Test
    @DisplayName("Should handle case insensitivity")
    void shouldHandleCaseInsensitivity() {
      assertThat(CommandCategorizer.categorize("get")).isEqualTo(CommandCategory.READ);
      assertThat(CommandCategorizer.categorize("Get")).isEqualTo(CommandCategory.READ);
      assertThat(CommandCategorizer.categorize("GET")).isEqualTo(CommandCategory.READ);
    }
  }

  @Nested
  @DisplayName("categorize() - WRITE commands")
  class WriteCommands {

    @Test
    @DisplayName("Should categorize SET as WRITE")
    void shouldCategorizeSet() {
      assertThat(CommandCategorizer.categorize("SET")).isEqualTo(CommandCategory.WRITE);
    }

    @Test
    @DisplayName("Should categorize HSET as WRITE")
    void shouldCategorizeHset() {
      assertThat(CommandCategorizer.categorize("HSET")).isEqualTo(CommandCategory.WRITE);
    }

    @Test
    @DisplayName("Should categorize LPUSH as WRITE")
    void shouldCategorizeLpush() {
      assertThat(CommandCategorizer.categorize("LPUSH")).isEqualTo(CommandCategory.WRITE);
    }

    @Test
    @DisplayName("Should categorize ZADD as WRITE")
    void shouldCategorizeZadd() {
      assertThat(CommandCategorizer.categorize("ZADD")).isEqualTo(CommandCategory.WRITE);
    }

    @Test
    @DisplayName("Should categorize SADD as WRITE")
    void shouldCategorizeSadd() {
      assertThat(CommandCategorizer.categorize("SADD")).isEqualTo(CommandCategory.WRITE);
    }

    @Test
    @DisplayName("Should categorize DEL as WRITE")
    void shouldCategorizeDel() {
      assertThat(CommandCategorizer.categorize("DEL")).isEqualTo(CommandCategory.WRITE);
    }

    @Test
    @DisplayName("Should categorize EXPIRE as WRITE")
    void shouldCategorizeExpire() {
      assertThat(CommandCategorizer.categorize("EXPIRE")).isEqualTo(CommandCategory.WRITE);
    }

    @Test
    @DisplayName("Should categorize INCR as WRITE")
    void shouldCategorizeIncr() {
      assertThat(CommandCategorizer.categorize("INCR")).isEqualTo(CommandCategory.WRITE);
    }
  }

  @Nested
  @DisplayName("categorize() - ADMIN commands")
  class AdminCommands {

    @Test
    @DisplayName("Should categorize PING as ADMIN")
    void shouldCategorizePing() {
      assertThat(CommandCategorizer.categorize("PING")).isEqualTo(CommandCategory.ADMIN);
    }

    @Test
    @DisplayName("Should categorize INFO as ADMIN")
    void shouldCategorizeInfo() {
      assertThat(CommandCategorizer.categorize("INFO")).isEqualTo(CommandCategory.ADMIN);
    }

    @Test
    @DisplayName("Should categorize CONFIG as ADMIN")
    void shouldCategorizeConfig() {
      assertThat(CommandCategorizer.categorize("CONFIG")).isEqualTo(CommandCategory.ADMIN);
    }

    @Test
    @DisplayName("Should categorize FLUSHDB as ADMIN")
    void shouldCategorizeFlushdb() {
      assertThat(CommandCategorizer.categorize("FLUSHDB")).isEqualTo(CommandCategory.ADMIN);
    }

    @Test
    @DisplayName("Should categorize CLIENT as ADMIN")
    void shouldCategorizeClient() {
      assertThat(CommandCategorizer.categorize("CLIENT")).isEqualTo(CommandCategory.ADMIN);
    }
  }

  @Nested
  @DisplayName("categorize() - PUBSUB commands")
  class PubSubCommands {

    @Test
    @DisplayName("Should categorize PUBLISH as PUBSUB")
    void shouldCategorizePublish() {
      assertThat(CommandCategorizer.categorize("PUBLISH")).isEqualTo(CommandCategory.PUBSUB);
    }

    @Test
    @DisplayName("Should categorize SUBSCRIBE as PUBSUB")
    void shouldCategorizeSubscribe() {
      assertThat(CommandCategorizer.categorize("SUBSCRIBE")).isEqualTo(CommandCategory.PUBSUB);
    }

    @Test
    @DisplayName("Should categorize PUBSUB as PUBSUB")
    void shouldCategorizePubsub() {
      assertThat(CommandCategorizer.categorize("PUBSUB")).isEqualTo(CommandCategory.PUBSUB);
    }
  }

  @Nested
  @DisplayName("categorize() - SCRIPTING commands")
  class ScriptingCommands {

    @Test
    @DisplayName("Should categorize EVAL as SCRIPTING")
    void shouldCategorizeEval() {
      assertThat(CommandCategorizer.categorize("EVAL")).isEqualTo(CommandCategory.SCRIPTING);
    }

    @Test
    @DisplayName("Should categorize EVALSHA as SCRIPTING")
    void shouldCategorizeEvalsha() {
      assertThat(CommandCategorizer.categorize("EVALSHA")).isEqualTo(CommandCategory.SCRIPTING);
    }

    @Test
    @DisplayName("Should categorize SCRIPT as SCRIPTING")
    void shouldCategorizeScript() {
      assertThat(CommandCategorizer.categorize("SCRIPT")).isEqualTo(CommandCategory.SCRIPTING);
    }

    @Test
    @DisplayName("Should categorize FCALL as SCRIPTING")
    void shouldCategorizeFcall() {
      assertThat(CommandCategorizer.categorize("FCALL")).isEqualTo(CommandCategory.SCRIPTING);
    }
  }

  @Nested
  @DisplayName("categorize() - TRANSACTION commands")
  class TransactionCommands {

    @Test
    @DisplayName("Should categorize MULTI as TRANSACTION")
    void shouldCategorizeMulti() {
      assertThat(CommandCategorizer.categorize("MULTI")).isEqualTo(CommandCategory.TRANSACTION);
    }

    @Test
    @DisplayName("Should categorize EXEC as TRANSACTION")
    void shouldCategorizeExec() {
      assertThat(CommandCategorizer.categorize("EXEC")).isEqualTo(CommandCategory.TRANSACTION);
    }

    @Test
    @DisplayName("Should categorize DISCARD as TRANSACTION")
    void shouldCategorizeDiscard() {
      assertThat(CommandCategorizer.categorize("DISCARD")).isEqualTo(CommandCategory.TRANSACTION);
    }

    @Test
    @DisplayName("Should categorize WATCH as TRANSACTION")
    void shouldCategorizeWatch() {
      assertThat(CommandCategorizer.categorize("WATCH")).isEqualTo(CommandCategory.TRANSACTION);
    }
  }

  @Nested
  @DisplayName("categorize() - STREAM commands")
  class StreamCommands {

    @Test
    @DisplayName("Should categorize XADD as STREAM")
    void shouldCategorizeXadd() {
      assertThat(CommandCategorizer.categorize("XADD")).isEqualTo(CommandCategory.STREAM);
    }

    @Test
    @DisplayName("Should categorize XREAD as STREAM")
    void shouldCategorizeXread() {
      assertThat(CommandCategorizer.categorize("XREAD")).isEqualTo(CommandCategory.STREAM);
    }

    @Test
    @DisplayName("Should categorize XGROUP as STREAM")
    void shouldCategorizeXgroup() {
      assertThat(CommandCategorizer.categorize("XGROUP")).isEqualTo(CommandCategory.STREAM);
    }
  }

  @Nested
  @DisplayName("categorize() - UNKNOWN commands")
  class UnknownCommands {

    @Test
    @DisplayName("Should return UNKNOWN for unrecognized command")
    void shouldReturnUnknownForUnrecognized() {
      assertThat(CommandCategorizer.categorize("NOTACOMMAND")).isEqualTo(CommandCategory.UNKNOWN);
    }

    @Test
    @DisplayName("Should return UNKNOWN for empty string")
    void shouldReturnUnknownForEmpty() {
      assertThat(CommandCategorizer.categorize("")).isEqualTo(CommandCategory.UNKNOWN);
    }
  }

  @Nested
  @DisplayName("categorize() - null handling")
  class NullHandling {

    @Test
    @DisplayName("Should throw NPE for null command")
    void shouldThrowForNull() {
      assertThatThrownBy(() -> CommandCategorizer.categorize(null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("isRead() shorthand")
  class IsReadShorthand {

    @Test
    @DisplayName("Should return true for GET")
    void shouldReturnTrueForGet() {
      assertThat(CommandCategorizer.isRead("GET")).isTrue();
    }

    @Test
    @DisplayName("Should return false for SET")
    void shouldReturnFalseForSet() {
      assertThat(CommandCategorizer.isRead("SET")).isFalse();
    }

    @Test
    @DisplayName("Should return false for PING")
    void shouldReturnFalseForPing() {
      assertThat(CommandCategorizer.isRead("PING")).isFalse();
    }
  }

  @Nested
  @DisplayName("isWrite() shorthand")
  class IsWriteShorthand {

    @Test
    @DisplayName("Should return true for SET")
    void shouldReturnTrueForSet() {
      assertThat(CommandCategorizer.isWrite("SET")).isTrue();
    }

    @Test
    @DisplayName("Should return false for GET")
    void shouldReturnFalseForGet() {
      assertThat(CommandCategorizer.isWrite("GET")).isFalse();
    }

    @Test
    @DisplayName("Should return false for PING")
    void shouldReturnFalseForPing() {
      assertThat(CommandCategorizer.isWrite("PING")).isFalse();
    }
  }
}
