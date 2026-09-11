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
package org.eclipse.fennec.mcp.emf.tools.tests;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;

import org.eclipse.fennec.mcp.api.MCPTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.osgi.framework.BundleContext;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.common.annotation.InjectService;
import org.osgi.test.junit5.context.BundleContextExtension;
import org.osgi.test.junit5.service.ServiceExtension;

/**
 * That the EMF model tools come up at all under OSGi.
 * <p>
 * {@code ModelGuard} is {@code configurationPolicy = REQUIRE} and every tool component has
 * a mandatory reference to it, so a guard that fails to activate takes all of them with it
 * - and a tool provider with an exact {@code tools.cardinality.minimum} then never binds,
 * which takes the whole endpoint down without an error anywhere. A bnd resolve cannot see
 * this: {@code -resolve.effective} skips {@code osgi.service}, so a component that throws
 * in {@code @Activate} resolves perfectly and simply never appears.
 */
@ExtendWith(BundleContextExtension.class)
@ExtendWith(ServiceExtension.class)
class EMFToolsActivationTest extends AbstractEMFToolsTest {

	@Test
	void theStructuralReadsAppearOnceTheGuardIsConfigured(@InjectBundleContext BundleContext context,
			@InjectService ConfigurationAdmin cm) throws IOException {
		configureGuard(cm, "*", "*");

		assertNotNull(awaitTool(context, "list_metamodel"));
		assertNotNull(awaitTool(context, "describe_eclass"));
		assertNotNull(awaitTool(context, "export_package"));
	}

	@Test
	void anUnqualifiedListingAnswers(@InjectBundleContext BundleContext context,
			@InjectService ConfigurationAdmin cm) throws IOException {
		configureGuard(cm, "*", "*");
		MCPTool listMetamodel = awaitTool(context, "list_metamodel");

		// No EPackages are deployed in this runtime, so the listing is empty - but it has
		// to be an answer rather than a failure, which is what proves the guard resolved
		// its registry through the ResourceSetFactory it actually bound.
		String result = call(listMetamodel, Map.of());

		assertTrue(result.contains("ePackages"), "expected a package listing, got: " + result);
	}
}
