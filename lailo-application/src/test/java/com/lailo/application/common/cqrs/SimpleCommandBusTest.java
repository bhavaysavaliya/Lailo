package com.lailo.application.common.cqrs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link SimpleCommandBus} routing and startup validation (pure logic, no I/O). */
class SimpleCommandBusTest {

    /**
     * Wraps handlers as the lazy provider the bus takes, which is how Spring supplies them.
     *
     * @param handlers the handlers the bus should find
     * @return a provider over exactly those handlers
     */
    private static ObjectProvider<CommandHandler<?, ?>> provider(CommandHandler<?, ?>... handlers) {
        List<CommandHandler<?, ?>> all = List.of(handlers);
        return new ObjectProvider<>() {
            /**
             * Never called: the bus only streams.
             *
             * @return never returns
             */
            @Override
            public CommandHandler<?, ?> getObject() {
                throw new UnsupportedOperationException("not needed by the bus");
            }

            /**
             * Streams the handlers.
             *
             * @return the handlers in declaration order
             */
            @Override
            public Stream<CommandHandler<?, ?>> stream() {
                return all.stream();
            }
        };
    }

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
        CommandBus bus = new SimpleCommandBus(provider(new PingHandler()));

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
        CommandBus bus = new SimpleCommandBus(provider(new PingHandler()));

        StepVerifier.create(bus.dispatch(new Unhandled()))
                .expectErrorMatches(e -> e instanceof IllegalStateException && e.getMessage().contains("Unhandled"))
                .verify();
    }

    /**
     * Two handlers for one command type are a startup error.
     * <ul>
     *   <li><b>Given</b> two {@code PingHandler} instances</li>
     *   <li><b>When</b> startup finishes and the bus builds its routing table</li>
     *   <li><b>Then</b> it throws {@code IllegalStateException} mentioning a duplicate handler, so the
     *       application never starts with ambiguous routing</li>
     * </ul>
     */
    @Test
    void rejectsDuplicateHandlersAtStartup() {
        SimpleCommandBus bus = new SimpleCommandBus(provider(new PingHandler(), new PingHandler()));

        assertThatThrownBy(bus::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate handler");
    }
}
