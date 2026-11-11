package com.lailo.application;

import com.lailo.application.support.IntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Smoke test: the full application context starts against the embedded infrastructure.
 *
 * <p>Named {@code *IT} rather than {@code *Test} because it boots embedded PostgreSQL, MongoDB, Redis and
 * Kafka, which is this project's definition of an integration test. Leaving it in the unit phase made the
 * build start that whole stack twice - once in the Surefire fork and again in the Failsafe fork - and the
 * second stack came up alongside the remains of the first, which showed up as the outbox relay stalling and
 * unrelated projection tests timing out.</p>
 */
class LailoApplicationIT extends IntegrationTest {

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
