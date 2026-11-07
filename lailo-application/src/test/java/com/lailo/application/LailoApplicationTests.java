package com.lailo.application;

import com.lailo.application.support.IntegrationTest;
import org.junit.jupiter.api.Test;

/** Smoke test: the full application context starts against the embedded infrastructure. */
class LailoApplicationTests extends IntegrationTest {

	/**
	 * The application context loads.
	 * <ul>
	 *   <li><b>Given</b> embedded PostgreSQL, MongoDB, Redis and Kafka and the {@code test} profile</li>
	 *   <li><b>When</b> Spring Boot starts the whole application</li>
	 *   <li><b>Then</b> every bean is created, Flyway migrations apply and no startup error occurs</li>
	 * </ul>
	 */
	@Test
	void contextLoads() {
	}

}
