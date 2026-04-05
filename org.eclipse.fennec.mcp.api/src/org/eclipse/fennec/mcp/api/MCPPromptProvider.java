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

import io.modelcontextprotocol.server.McpServerFeatures.AsyncPromptSpecification;

/**
 * Whiteboard provider for MCP prompt specifications. Implementations
 * register as OSGi services and are collected by the {@link MCPServer}
 * to expose reusable prompt templates to MCP clients.
 *
 * @author ilenia
 * @since Dec 3, 2025
 */
@ProviderType
public interface MCPPromptProvider {

	/**
	 * Returns the prompt specifications provided by this service.
	 * @return list of async prompt specifications, never {@code null}
	 */
	List<AsyncPromptSpecification> getMCPPrompts();

}
