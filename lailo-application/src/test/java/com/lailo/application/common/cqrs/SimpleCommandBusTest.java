package com.lailo.application.common.cqrs;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimpleCommandBusTest {

    record Ping(String value) implements Command<String> {
    }

    record Unhandled() implements Command<Void> {
    }

    static class PingHandler implements CommandHandler<Ping, String> {
        @Override
        public Mono<String> handle(Ping command) {
            return Mono.just("pong:" + command.value());
        }

        @Override
        public Class<Ping> commandType() {
            return Ping.class;
        }
    }

    @Test
    void routesCommandToItsHandler() {
        CommandBus bus = new SimpleCommandBus(List.of(new PingHandler()));

        StepVerifier.create(bus.dispatch(new Ping("x"))).expectNext("pong:x").verifyComplete();
    }

    @Test
    void errorsWhenNoHandlerIsRegistered() {
        CommandBus bus = new SimpleCommandBus(List.of(new PingHandler()));

        StepVerifier.create(bus.dispatch(new Unhandled()))
                .expectErrorMatches(e -> e instanceof IllegalStateException && e.getMessage().contains("Unhandled"))
                .verify();
    }

    @Test
    void rejectsDuplicateHandlersAtStartup() {
        assertThatThrownBy(() -> new SimpleCommandBus(List.of(new PingHandler(), new PingHandler())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate handler");
    }
}
