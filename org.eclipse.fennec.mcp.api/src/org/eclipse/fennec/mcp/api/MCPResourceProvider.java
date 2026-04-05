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

import io.modelcontextprotocol.server.McpServerFeatures.AsyncResourceSpecification;

/**
 * Whiteboard provider for MCP resource specifications. Implementations
 * register as OSGi services and are collected by the {@link MCPServer}
 * to expose data resources (files, URIs, etc.) to MCP clients.
 *
 * @author ilenia
 * @since Dec 3, 2025
 */
@ProviderType
public interface MCPResourceProvider {

	/**
	 * Returns the resource specifications provided by this service.
	 * @return list of async resource specifications, never {@code null}
	 */
	List<AsyncResourceSpecification> getMCPResources();

}
