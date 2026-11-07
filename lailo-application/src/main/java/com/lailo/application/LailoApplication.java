package com.lailo.application;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot entry point of the Lailo backend (auth API, CQRS handlers, outbox relay, Kafka projectors). */
@SpringBootApplication
public class LailoApplication {

	/**
	 * Starts the reactive (Netty) application.
	 *
	 * @param args command-line arguments, passed through to Spring Boot (e.g. {@code --server.port=8081})
	 */
	public static void main(String[] args) {
		SpringApplication.run(LailoApplication.class, args);
	}

}
