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

import java.util.Hashtable;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.fennec.mcp.emf.tools.runtime.DatasetDTO;
import org.eclipse.fennec.mcp.emf.tools.runtime.EMFToolsRuntimeDTO;
import org.eclipse.fennec.mcp.emf.tools.runtime.EMFToolsServiceRuntime;
import org.eclipse.fennec.mcp.emf.tools.runtime.GuardPolicyDTO;
import org.eclipse.fennec.mcp.emf.tools.runtime.RegisteredPackageDTO;
import org.eclipse.fennec.mcp.emf.tools.runtime.SessionDTO;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;

/**
 * OSGi service-runtime introspection of the EMF MCP tools (the pattern of
 * {@code HttpServiceRuntime}): registers {@link EMFToolsServiceRuntime}
 * manually so it can bump the {@link Constants#SERVICE_CHANGECOUNT
 * service.changecount} property via
 * {@link ServiceRegistration#setProperties(java.util.Dictionary)} whenever
 * sessions, datasets, registered packages or the guard policy change.
 * Consumers listen for the service-modified event and re-fetch the DTO
 * instead of polling.
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
@Component(name = "EMFToolsServiceRuntime", immediate = true, service = {})
public class EMFToolsRuntimeComponent implements EMFToolsServiceRuntime {

	@Reference
	DatasetRegistry datasets;

	@Reference
	PackageRegistry packages;

	/** Optional: absent in minimal runtimes without a configured guard. */
	private volatile ModelGuard guard;

	private final AtomicLong changeCount = new AtomicLong();
	private volatile ServiceRegistration<EMFToolsServiceRuntime> registration;

	@Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
	void setGuard(ModelGuard guard) {
		this.guard = guard;
		guard.onPolicyChange(this::bump);
		bump();
	}

	void unsetGuard(ModelGuard guard) {
		if (this.guard == guard) {
			this.guard = null;
			bump();
		}
	}

	@Activate
	void activate(BundleContext context) {
		datasets.onChange(this::bump);
		packages.onChange(this::bump);
		registration = context.registerService(EMFToolsServiceRuntime.class, this, properties());
	}

	@Deactivate
	void deactivate() {
		ServiceRegistration<EMFToolsServiceRuntime> local = registration;
		registration = null;
		if (local != null) {
			local.unregister();
		}
	}

	@Override
	public EMFToolsRuntimeDTO getRuntimeDTO() {
		EMFToolsRuntimeDTO dto = new EMFToolsRuntimeDTO();
		ModelGuard localGuard = guard;
		if (localGuard != null) {
			GuardPolicyDTO guardPolicy = new GuardPolicyDTO();
			guardPolicy.allowedEPackages = localGuard.packageAllowListSnapshot();
			guardPolicy.allowedEClasses = localGuard.classAllowListSnapshot();
			dto.guardPolicy = guardPolicy;
		}
		dto.packagePolicy = packages.policySnapshot();
		dto.sessions = mergeSessions(datasets.sessionSnapshots(), packages.sessionSnapshots());
		return dto;
	}

	private static SessionDTO[] mergeSessions(Map<String, SessionDTO> datasetSide, Map<String, SessionDTO> packageSide) {
		Map<String, SessionDTO> merged = new TreeMap<>(datasetSide);
		packageSide.forEach((sessionId, packageSession) -> merged.merge(sessionId, packageSession, (a, b) -> {
			a.registeredPackages = b.registeredPackages;
			a.lastAccess = Math.max(a.lastAccess, b.lastAccess);
			return a;
		}));
		merged.values().forEach(session -> {
			if (session.datasets == null) {
				session.datasets = new DatasetDTO[0];
			}
			if (session.registeredPackages == null) {
				session.registeredPackages = new RegisteredPackageDTO[0];
			}
		});
		return merged.values().toArray(SessionDTO[]::new);
	}

	private void bump() {
		ServiceRegistration<EMFToolsServiceRuntime> local = registration;
		if (local != null) {
			local.setProperties(properties());
		}
	}

	private Hashtable<String, Object> properties() {
		Hashtable<String, Object> properties = new Hashtable<>();
		properties.put(Constants.SERVICE_CHANGECOUNT, changeCount.getAndIncrement());
		properties.put(Constants.SERVICE_DESCRIPTION, "Runtime introspection of the EMF MCP tools");
		return properties;
	}
}
