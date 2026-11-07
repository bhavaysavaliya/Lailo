package com.lailo.application.common.cqrs;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link SimpleCommandBus} routing and startup validation (pure logic, no I/O). */
class SimpleCommandBusTest {

    /**
     * Test command carrying a value.
     *
     * @param value echoed back by {@link PingHandler}
     */
    record Ping(String value) implements Command<String> {
    }

    /** Test command with no registered handler. */
    record Unhandled() implements Command<Void> {
    }

    /** Handler for {@link Ping} that answers {@code pong:<value>}. */
    static class PingHandler implements CommandHandler<Ping, String> {
        /**
         * Replies with {@code pong:} followed by the command's value.
         *
         * @param command the ping
         * @return the reply
         */
        @Override
        public Mono<String> handle(Ping command) {
            return Mono.just("pong:" + command.value());
        }

        /**
         * Serves {@link Ping}.
         *
         * @return the command class
         */
        @Override
        public Class<Ping> commandType() {
            return Ping.class;
        }
    }

    /**
     * A command reaches the handler registered for its type.
     * <ul>
     *   <li><b>Given</b> a bus with a {@code PingHandler}</li>
     *   <li><b>When</b> a {@code Ping("x")} is dispatched</li>
     *   <li><b>Then</b> the result is {@code pong:x}</li>
     * </ul>
     */
    @Test
    void routesCommandToItsHandler() {
        CommandBus bus = new SimpleCommandBus(List.of(new PingHandler()));

        StepVerifier.create(bus.dispatch(new Ping("x"))).expectNext("pong:x").verifyComplete();
    }

    /**
     * Dispatching a command nobody handles fails clearly.
     * <ul>
     *   <li><b>Given</b> a bus without a handler for {@code Unhandled}</li>
     *   <li><b>When</b> an {@code Unhandled} command is dispatched</li>
     *   <li><b>Then</b> the result errors with {@code IllegalStateException} naming the command type</li>
     * </ul>
     */
    @Test
    void errorsWhenNoHandlerIsRegistered() {
        CommandBus bus = new SimpleCommandBus(List.of(new PingHandler()));

        StepVerifier.create(bus.dispatch(new Unhandled()))
                .expectErrorMatches(e -> e instanceof IllegalStateException && e.getMessage().contains("Unhandled"))
                .verify();
    }

    /**
     * Two handlers for one command type are a startup error.
     * <ul>
     *   <li><b>Given</b> two {@code PingHandler} instances</li>
     *   <li><b>When</b> the bus is constructed</li>
     *   <li><b>Then</b> construction throws {@code IllegalStateException} mentioning a duplicate handler</li>
     * </ul>
     */
    @Test
    void rejectsDuplicateHandlersAtStartup() {
        assertThatThrownBy(() -> new SimpleCommandBus(List.of(new PingHandler(), new PingHandler())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate handler");
    }
}
