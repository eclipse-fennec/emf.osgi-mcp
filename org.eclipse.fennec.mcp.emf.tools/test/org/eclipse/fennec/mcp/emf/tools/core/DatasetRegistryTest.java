/*
 * ******************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Data In Motion Consulting - initial implementation
 * ******************************************************************
 */
package org.eclipse.fennec.mcp.emf.tools.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.impl.EPackageRegistryImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.fennec.emf.osgi.ResourceSetFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests session scoping, ownership isolation and caps of the
 * {@link DatasetRegistry}.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
class DatasetRegistryTest {

	private DatasetRegistry registry;

	@BeforeEach
	void setUp() {
		EPackage.Registry packageRegistry = TestModels.registryWith(TestModels.libraryPackage());
		ResourceSetFactory factory = () -> {
			ResourceSetImpl resourceSet = new ResourceSetImpl();
			resourceSet.setPackageRegistry(new EPackageRegistryImpl(packageRegistry));
			return resourceSet;
		};
		registry = new DatasetRegistry(factory, new DatasetLimits(2, 100, 1000, 1000, 4096, 4096, 60_000L));
	}

	@Test
	void datasetsAreSessionIsolated() {
		Dataset dataset = registry.create("session-a", null);
		assertThat(registry.require("session-a", dataset.getId())).isSameAs(dataset);
		// another session must not see or address the dataset (IDOR guard)
		assertThatThrownBy(() -> registry.require("session-b", dataset.getId()))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("Unknown datasetId");
		assertThat(registry.list("session-b")).isEmpty();
		assertThat(registry.delete("session-b", dataset.getId())).isFalse();
		assertThat(registry.delete("session-a", dataset.getId())).isTrue();
	}

	@Test
	void datasetIdsAreUnguessableUuids() {
		Dataset dataset = registry.create("session-a", null);
		assertThat(dataset.getId()).hasSize(36).contains("-");
	}

	@Test
	void perSessionDatasetCapIsEnforced() {
		registry.create("session-a", null);
		registry.create("session-a", null);
		assertThatThrownBy(() -> registry.create("session-a", null))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("limit");
		// other sessions are unaffected
		assertThat(registry.create("session-b", null)).isNotNull();
	}

	@Test
	void perSessionCapHoldsUnderConcurrentCreates() throws Exception {
		// cap is 2 (see setUp). Many threads racing create() on one session must
		// never overshoot the cap — regression guard for the atomic check+insert.
		int threads = 16;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger created = new AtomicInteger();
		try {
			for (int i = 0; i < threads; i++) {
				pool.submit(() -> {
					start.await();
					try {
						registry.create("session-race", null);
						created.incrementAndGet();
					} catch (ToolException expectedOnceCapReached) {
						// over-cap creates are rejected, as intended
					}
					return null;
				});
			}
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		} finally {
			pool.shutdownNow();
		}
		assertThat(created.get()).isEqualTo(2);
		assertThat(registry.list("session-race")).hasSize(2);
	}

	@Test
	void missingSessionIsRejected() {
		assertThatThrownBy(() -> registry.create(null, null))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("session");
		assertThatThrownBy(() -> registry.create(" ", null))
				.isInstanceOf(ToolException.class);
	}
}
