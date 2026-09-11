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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.junit5.context.BundleContextExtension;

/**
 * Pins the service property that identifies the <em>default</em>
 * {@code ResourceSetFactory}, because a deployment has to be able to name it.
 * <p>
 * {@code ModelGuard} takes a single {@code ResourceSetFactory} and reads its package
 * registry. In a runtime with the model.atlas client that is not the only one:
 * {@code AtlasEPackageRegistryConfigurator} generates an {@code EPackageRegistry} +
 * {@code ResourceSetFactory} pair per configured Atlas scope, so an untargeted reference
 * binds an arbitrary factory - highest ranking, then lowest service id. Such a deployment
 * pins it with {@code resourceSetFactory.target} on the {@code EMFModelGuard} PID, and
 * this is the filter that has to keep working:
 *
 * <pre>
 * "resourceSetFactory.target": "(component.name=DefaultResourcesetFactory)"
 * </pre>
 *
 * The failure mode if that name ever changes is the quiet one worth a test: the filter
 * matches nothing, {@code ModelGuard} never satisfies its mandatory reference, every EMF
 * tool disappears, and a tool provider with an exact {@code tools.cardinality.minimum}
 * takes the endpoint down without an error anywhere.
 */
@ExtendWith(BundleContextExtension.class)
class DefaultResourceSetFactoryTest {

	/** The component name a deployment filters on. Note the lowercase 's' in "Resourceset". */
	private static final String DEFAULT_FACTORY = "DefaultResourcesetFactory";

	@Test
	void theDefaultFactoryIsAddressableByComponentName(@InjectBundleContext BundleContext context) throws Exception {
		ServiceReference<?>[] references = context.getAllServiceReferences(
				"org.eclipse.fennec.emf.osgi.ResourceSetFactory",
				String.format("(component.name=%s)", DEFAULT_FACTORY));

		assertNotNull(references, "no ResourceSetFactory matches (component.name=" + DEFAULT_FACTORY
				+ "). A deployment that pins resourceSetFactory.target to this filter would leave "
				+ "ModelGuard unsatisfied and every EMF tool unregistered.");
		assertEquals(1, references.length, "expected exactly one default ResourceSetFactory");
	}
}
