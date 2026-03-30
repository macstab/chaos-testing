/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.inspector.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.macstab.chaos.redis.util.inspector.executor.RedisCommandExecutionException;

import io.lettuce.core.api.sync.RedisCommands;

/** Comprehensive unit tests for {@link LettuceRedisCommandExecutor}. */
@DisplayName("LettuceRedisCommandExecutor")
@ExtendWith(MockitoExtension.class)
class LettuceRedisCommandExecutorTest {

  @Mock RedisCommands<String, String> redisCommands;

  @Nested
  @DisplayName("Constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("Should throw NPE for null redisCommands")
    void shouldThrowForNullRedisCommands() {
      // ARRANGE / ACT / ASSERT
      assertThatThrownBy(() -> new LettuceRedisCommandExecutor(null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should accept valid redisCommands")
    void shouldAcceptValidRedisCommands() {
      // ARRANGE / ACT
      final LettuceRedisCommandExecutor executor = new LettuceRedisCommandExecutor(redisCommands);

      // ASSERT
      assertThat(executor).isNotNull();
    }
  }

  @Nested
  @DisplayName("execute() — CLIENT LIST")
  class ExecuteClientList {

    @Test
    @DisplayName("Should execute CLIENT LIST and return result")
    void shouldExecuteClientList() {
      // ARRANGE
      final String expectedResult = "id=1 addr=127.0.0.1:12345 name= age=0 idle=0";
      when(redisCommands.clientList()).thenReturn(expectedResult);
      final LettuceRedisCommandExecutor executor = new LettuceRedisCommandExecutor(redisCommands);

      // ACT
      final String result = executor.execute("CLIENT LIST");

      // ASSERT
      assertThat(result).isEqualTo(expectedResult);
      verify(redisCommands).clientList();
    }

    @Test
    @DisplayName("Should return empty string when clientList returns null")
    void shouldReturnEmptyWhenNull() {
      // ARRANGE
      when(redisCommands.clientList()).thenReturn(null);
      final LettuceRedisCommandExecutor executor = new LettuceRedisCommandExecutor(redisCommands);

      // ACT
      final String result = executor.execute("CLIENT LIST");

      // ASSERT
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("execute() — INFO")
  class ExecuteInfo {

    @Test
    @DisplayName("Should execute INFO memory and return result")
    void shouldExecuteInfoMemory() {
      // ARRANGE
      final String expectedResult = "# Memory\r\nused_memory:1024\r\n";
      when(redisCommands.info("memory")).thenReturn(expectedResult);
      final LettuceRedisCommandExecutor executor = new LettuceRedisCommandExecutor(redisCommands);

      // ACT
      final String result = executor.execute("INFO memory");

      // ASSERT
      assertThat(result).isEqualTo(expectedResult);
      verify(redisCommands).info("memory");
    }

    @Test
    @DisplayName("Should execute INFO alone as INFO all")
    void shouldExecuteInfoAll() {
      // ARRANGE
      final String expectedResult = "# Server\r\nredis_version:7.4.0\r\n";
      when(redisCommands.info("all")).thenReturn(expectedResult);
      final LettuceRedisCommandExecutor executor = new LettuceRedisCommandExecutor(redisCommands);

      // ACT
      final String result = executor.execute("INFO");

      // ASSERT
      assertThat(result).isEqualTo(expectedResult);
      verify(redisCommands).info("all");
    }

    @Test
    @DisplayName("Should execute INFO replication")
    void shouldExecuteInfoReplication() {
      // ARRANGE
      final String expectedResult = "# Replication\r\nrole:master\r\n";
      when(redisCommands.info("replication")).thenReturn(expectedResult);
      final LettuceRedisCommandExecutor executor = new LettuceRedisCommandExecutor(redisCommands);

      // ACT
      final String result = executor.execute("INFO replication");

      // ASSERT
      assertThat(result).isEqualTo(expectedResult);
      verify(redisCommands).info("replication");
    }
  }

  @Nested
  @DisplayName("execute() — SLOWLOG RESET")
  class ExecuteSlowlogReset {

    @Test
    @DisplayName("Should execute SLOWLOG RESET and return OK")
    void shouldExecuteSlowlogReset() {
      // ARRANGE
      when(redisCommands.slowlogReset()).thenReturn("OK");
      final LettuceRedisCommandExecutor executor = new LettuceRedisCommandExecutor(redisCommands);

      // ACT
      final String result = executor.execute("SLOWLOG RESET");

      // ASSERT
      assertThat(result).isEqualTo("OK");
      verify(redisCommands).slowlogReset();
    }
  }

  @Nested
  @DisplayName("execute() — SLOWLOG GET")
  class ExecuteSlowlogGet {

    @Test
    @DisplayName("Should throw RedisCommandExecutionException for SLOWLOG GET")
    void shouldThrowForSlowlogGet() {
      // ARRANGE
      final LettuceRedisCommandExecutor executor = new LettuceRedisCommandExecutor(redisCommands);

      // ACT / ASSERT
      assertThatThrownBy(() -> executor.execute("SLOWLOG GET"))
          .isInstanceOf(RedisCommandExecutionException.class)
          .hasMessageContaining("SLOWLOG GET");
    }
  }

  @Nested
  @DisplayName("execute() — SET")
  class ExecuteSet {

    @Test
    @DisplayName("Should execute SET command")
    void shouldExecuteSet() {
      // ARRANGE
      when(redisCommands.set("mykey", "myvalue")).thenReturn("OK");
      final LettuceRedisCommandExecutor executor = new LettuceRedisCommandExecutor(redisCommands);

      // ACT
      final String result = executor.execute("SET mykey myvalue");

      // ASSERT
      assertThat(result).isEqualTo("OK");
      verify(redisCommands).set("mykey", "myvalue");
    }
  }

  @Nested
  @DisplayName("execute() — GET")
  class ExecuteGet {

    @Test
    @DisplayName("Should execute GET command")
    void shouldExecuteGet() {
      // ARRANGE
      when(redisCommands.get("mykey")).thenReturn("myvalue");
      final LettuceRedisCommandExecutor executor = new LettuceRedisCommandExecutor(redisCommands);

      // ACT
      final String result = executor.execute("GET mykey");

      // ASSERT
      assertThat(result).isEqualTo("myvalue");
      verify(redisCommands).get("mykey");
    }
  }

  @Nested
  @DisplayName("execute() — DEL")
  class ExecuteDel {

    @Test
    @DisplayName("Should execute DEL command")
    void shouldExecuteDel() {
      // ARRANGE
      when(redisCommands.del("mykey")).thenReturn(1L);
      final LettuceRedisCommandExecutor executor = new LettuceRedisCommandExecutor(redisCommands);

      // ACT
      final String result = executor.execute("DEL mykey");

      // ASSERT
      assertThat(result).isEqualTo("1");
      verify(redisCommands).del("mykey");
    }
  }

  @Nested
  @DisplayName("execute() — unsupported command")
  class ExecuteUnsupported {

    @Test
    @DisplayName("Should throw RedisCommandExecutionException for unsupported command")
    void shouldThrowForUnsupportedCommand() {
      // ARRANGE
      final LettuceRedisCommandExecutor executor = new LettuceRedisCommandExecutor(redisCommands);

      // ACT / ASSERT
      assertThatThrownBy(() -> executor.execute("UNSUPPORTED COMMAND"))
          .isInstanceOf(RedisCommandExecutionException.class);
    }
  }

  @Nested
  @DisplayName("execute() — null command")
  class ExecuteNull {

    @Test
    @DisplayName("Should throw NPE for null command")
    void shouldThrowForNullCommand() {
      // ARRANGE
      final LettuceRedisCommandExecutor executor = new LettuceRedisCommandExecutor(redisCommands);

      // ACT / ASSERT
      assertThatThrownBy(() -> executor.execute(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("execute() — case insensitive")
  class ExecuteCaseInsensitive {

    @Test
    @DisplayName("Should handle lowercase commands")
    void shouldHandleLowercase() {
      // ARRANGE
      final String expectedResult = "id=1 addr=127.0.0.1:12345";
      when(redisCommands.clientList()).thenReturn(expectedResult);
      final LettuceRedisCommandExecutor executor = new LettuceRedisCommandExecutor(redisCommands);

      // ACT
      final String result = executor.execute("client list");

      // ASSERT
      assertThat(result).isEqualTo(expectedResult);
      verify(redisCommands).clientList();
    }
  }

  @Nested
  @DisplayName("close() — borrowed connection")
  class CloseBorrowedConnection {

    @Test
    @DisplayName("Should not interact with connection on close")
    void shouldNotInteractOnClose() {
      // ARRANGE
      final LettuceRedisCommandExecutor executor = new LettuceRedisCommandExecutor(redisCommands);

      // ACT
      executor.close();

      // ASSERT
      verifyNoInteractions(redisCommands);
    }
  }

  @Nested
  @DisplayName("getRedisCommands()")
  class GetRedisCommands {

    @Test
    @DisplayName("Should return the injected commands instance")
    void shouldReturnInjectedCommands() {
      // ARRANGE
      final LettuceRedisCommandExecutor executor = new LettuceRedisCommandExecutor(redisCommands);

      // ACT
      final RedisCommands<String, String> result = executor.getRedisCommands();

      // ASSERT
      assertThat(result).isSameAs(redisCommands);
    }
  }
}
