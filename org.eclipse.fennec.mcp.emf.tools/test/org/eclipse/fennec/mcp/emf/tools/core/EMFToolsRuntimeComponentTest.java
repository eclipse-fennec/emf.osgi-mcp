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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Dictionary;
import java.util.List;
import java.util.Set;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.impl.EPackageRegistryImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.fennec.emf.osgi.ResourceSetFactory;
import org.eclipse.fennec.mcp.emf.tools.runtime.EMFToolsRuntimeDTO;
import org.eclipse.fennec.mcp.emf.tools.runtime.EMFToolsServiceRuntime;
import org.eclipse.fennec.mcp.emf.tools.runtime.SessionDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;

/**
 * Tests DTO assembly and the {@code service.changecount} contract of the
 * {@link EMFToolsRuntimeComponent}.
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
class EMFToolsRuntimeComponentTest {

	private EMFToolsRuntimeComponent runtime;
	private DatasetRegistry datasets;
	private PackageRegistry packages;
	private BundleContext context;
	private ServiceRegistration<EMFToolsServiceRuntime> registration;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		ResourceSetFactory factory = () -> {
			ResourceSetImpl resourceSet = new ResourceSetImpl();
			resourceSet.setPackageRegistry(new EPackageRegistryImpl());
			return resourceSet;
		};
		datasets = new DatasetRegistry(factory, DatasetLimits.defaults());
		packages = new PackageRegistry(Set.of("*"), Set.of("http://deny.org/*"), 100);
		runtime = new EMFToolsRuntimeComponent();
		runtime.datasets = datasets;
		runtime.packages = packages;
		context = mock(BundleContext.class);
		registration = mock(ServiceRegistration.class);
		when(context.registerService(eq(EMFToolsServiceRuntime.class), same(runtime), any())).thenReturn(registration);
		runtime.activate(context);
	}

	private static EPackage pkg(String nsUri) {
		EPackage ePackage = EcoreFactory.eINSTANCE.createEPackage();
		ePackage.setName("p");
		ePackage.setNsPrefix("p");
		ePackage.setNsURI(nsUri);
		EClass thing = EcoreFactory.eINSTANCE.createEClass();
		thing.setName("Thing");
		ePackage.getEClassifiers().add(thing);
		return ePackage;
	}

	@Test
	void runtimeDtoReflectsSessionsPoliciesAndTools() {
		Dataset dataset = datasets.create("session-1", null);
		packages.register("session-1", pkg("http://example.org/a"));
		ModelGuard guard = new ModelGuard(new EPackageRegistryImpl(), Set.of("http://allowed.org/x"), Set.of());
		runtime.setGuard(guard);

		EMFToolsRuntimeDTO dto = runtime.getRuntimeDTO();

		assertThat(dto.guardPolicy.allowedEPackages).containsExactly("http://allowed.org/x");
		assertThat(dto.packagePolicy.nsUriAllowList).containsExactly("*");
		assertThat(dto.packagePolicy.nsUriDenyList).containsExactly("http://deny.org/*");
		assertThat(dto.packagePolicy.maxModels).isEqualTo(100);

		assertThat(dto.sessions).hasSize(1);
		SessionDTO session = dto.sessions[0];
		assertThat(session.sessionId).isEqualTo("session-1");
		assertThat(session.datasets).extracting(d -> d.datasetId).containsExactly(dataset.getId());
		assertThat(session.registeredPackages).extracting(p -> p.nsUri).containsExactly("http://example.org/a");
	}

	@Test
	void sessionsWithOnlyPackagesOrOnlyDatasetsAreMerged() {
		datasets.create("session-a", null);
		packages.register("session-b", pkg("http://example.org/b"));

		EMFToolsRuntimeDTO dto = runtime.getRuntimeDTO();

		assertThat(dto.sessions).extracting(s -> s.sessionId).containsExactly("session-a", "session-b");
		assertThat(dto.sessions[0].registeredPackages).isEmpty();
		assertThat(dto.sessions[1].datasets).isEmpty();
	}

	@Test
	@SuppressWarnings("unchecked")
	void changesBumpTheServiceChangecount() {
		datasets.create("session-1", null);
		packages.register("session-1", pkg("http://example.org/a"));

		ArgumentCaptor<Dictionary<String, ?>> properties = ArgumentCaptor.forClass(Dictionary.class);
		verify(registration, atLeastOnce()).setProperties(properties.capture());
		List<Long> counts = properties.getAllValues().stream()
				.map(p -> (Long) p.get(Constants.SERVICE_CHANGECOUNT))
				.toList();
		assertThat(counts).hasSizeGreaterThanOrEqualTo(2).isSorted().doesNotHaveDuplicates();
	}

	@Test
	void deactivateUnregistersTheService() {
		runtime.deactivate();
		verify(registration).unregister();
		// a change after deactivation must not touch the stale registration
		datasets.create("session-1", null);
		verify(registration, atLeastOnce()).unregister();
	}
}
