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
package org.eclipse.fennec.mcp.api.runtime;

import org.osgi.annotation.versioning.ProviderType;

/**
 * Introspection of the MCP whiteboard, following the OSGi service runtime
 * pattern (cf. {@code HttpServiceRuntime}): the service is registered with a
 * {@code service.changecount} property that is incremented whenever the
 * runtime DTO may have changed, so interested parties can listen for service
 * modifications instead of polling.
 * <p>
 * The DTO is a point-in-time snapshot of the whiteboard: the active
 * {@code MCPServer}s, the {@code MCPToolProvider}s with the tools they
 * matched, and every {@code MCPTool} service currently registered. Tools do
 * not need to implement anything for this — they are discovered as
 * whiteboard services.
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
@ProviderType
public interface MCPServiceRuntime {

	/**
	 * @return a snapshot of the current MCP whiteboard state, never {@code null}
	 */
	MCPRuntimeDTO getRuntimeDTO();
}
