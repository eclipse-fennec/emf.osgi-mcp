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
package org.eclipse.fennec.mcp.endpoint;

import org.osgi.annotation.versioning.ProviderType;

/**
 * An addressable MCP server: a name and a URL, and nothing about who hosts it.
 * <p>
 * This is the half of {@link MCPServer} a <em>client</em> needs. A client that
 * only has to reach an MCP server — to name it in a request and open a
 * connection to it — needs no tool, prompt or resource aggregation, and should
 * not have to be satisfied by a locally hosted server component in order to talk
 * to one on another host.
 * <p>
 * Two kinds of service implement this: a server this framework hosts, which is
 * also an {@link MCPServer}, and a remote one, which is only ever an endpoint.
 * Binding to {@code MCPEndpoint} makes a consumer indifferent to which it got.
 *
 * @author ilenia
 * @since Aug 27, 2026
 */
@ProviderType
public interface MCPEndpoint {

	/**
	 * @return the human-readable server name used for MCP server identification
	 */
	String getServerName();

	/**
	 * @return the complete URL at which this MCP server is reachable by clients
	 */
	String getServerFullUrl();
}
