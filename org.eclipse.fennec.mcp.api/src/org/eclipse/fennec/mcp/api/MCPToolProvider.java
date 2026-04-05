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

import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;

/**
 * Whiteboard aggregator that collects multiple {@link MCPTool} services and
 * converts them into MCP SDK {@link AsyncToolSpecification} objects.
 * <p>
 * Tool selection is controlled via LDAP target filters in the configuration,
 * allowing different providers to serve different subsets of tools.
 *
 * @author ilenia
 * @since Dec 3, 2025
 */
@ProviderType
public interface MCPToolProvider {

	/**
	 * Returns all collected tools as async MCP tool specifications ready
	 * for registration with the MCP server.
	 * @return list of async tool specifications, never {@code null}
	 */
	List<AsyncToolSpecification> getMCPTools();

	/**
	 * @return a human-readable description of what kinds of tools this provider collects
	 */
	String getDescription();
}
