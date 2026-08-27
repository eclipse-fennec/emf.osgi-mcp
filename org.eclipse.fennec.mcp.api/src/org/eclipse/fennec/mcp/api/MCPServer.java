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
package org.eclipse.fennec.mcp.api;

import java.util.List;

import org.osgi.annotation.versioning.ProviderType;

import io.modelcontextprotocol.server.McpServerFeatures;

/**
 * Top-level MCP server interface exposing tools, prompts, and resources
 * to MCP clients. Implementations collect these specifications from
 * whiteboard-registered providers and serve them over a transport (e.g. HTTP).
 * <p>
 * Extends {@link MCPEndpoint}: a hosted server is also an addressable one, and
 * inherits {@code getServerName()} / {@code getServerFullUrl()} from it. A
 * consumer that only needs to <em>address</em> a server should bind to
 * {@code MCPEndpoint} instead, so a remote server satisfies it too.
 *
 * @author ilenia
 * @since 1.0
 */
@ProviderType
public interface MCPServer extends MCPEndpoint {

	/**
	 * Returns all tool specifications aggregated from registered {@link MCPToolProvider} services.
	 * @return list of async tool specifications, never {@code null}
	 */
	List<McpServerFeatures.AsyncToolSpecification> getTools();

	/**
	 * Returns all prompt specifications aggregated from registered {@link MCPPromptProvider} services.
	 * @return list of async prompt specifications, never {@code null}
	 */
	List<McpServerFeatures.AsyncPromptSpecification> getPrompts();

	/**
	 * Returns all resource specifications aggregated from registered {@link MCPResourceProvider} services.
	 * @return list of async resource specifications, never {@code null}
	 */
	List<McpServerFeatures.AsyncResourceSpecification> getResources();

}
