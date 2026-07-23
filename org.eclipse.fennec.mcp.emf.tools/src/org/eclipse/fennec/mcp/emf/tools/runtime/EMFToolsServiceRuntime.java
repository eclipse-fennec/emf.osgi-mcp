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
package org.eclipse.fennec.mcp.emf.tools.runtime;

import org.osgi.annotation.versioning.ProviderType;

/**
 * Introspection of the EMF MCP tools runtime, following the OSGi service
 * runtime pattern (cf. {@code HttpServiceRuntime}): the service is registered
 * with a {@code service.changecount} property that is incremented whenever the
 * runtime DTO may have changed, so interested parties can listen for service
 * modifications instead of polling.
 * <p>
 * The DTO is a point-in-time snapshot of the guard policy (allow-listed
 * packages/classes), the package registry policy (nsURI allow/deny lists) and
 * every session with its datasets and session-registered packages. Tool-level
 * introspection is owned by the generic
 * {@code org.eclipse.fennec.mcp.api.runtime.MCPServiceRuntime}. Object/recipe
 * counts inside a dataset change without a changecount bump — they are
 * refreshed on every {@link #getRuntimeDTO()} call.
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
@ProviderType
public interface EMFToolsServiceRuntime {

	/**
	 * @return a snapshot of the current runtime state, never {@code null}
	 */
	EMFToolsRuntimeDTO getRuntimeDTO();
}
