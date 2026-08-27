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

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * Configuration of a {@link RemoteMCPEndpoint}. Both properties are required:
 * an endpoint is nothing but a name and a URL, and neither has a default that
 * could be meaningful.
 *
 * @author ilenia
 * @since Aug 27, 2026
 */
@ObjectClassDefinition(name = "Remote MCP Endpoint", description = "Registers an MCP server hosted elsewhere as an addressable MCPEndpoint, so a client can reach it without this framework hosting anything.")
public @interface RemoteMCPEndpointConfig {

	@AttributeDefinition(name = "Server Name", description = "The name of the remote MCP server, used for MCP server identification and as the service property clients filter on.")
	String server_name();

	@AttributeDefinition(name = "Server URL", description = "The complete URL at which the remote MCP server is reachable, e.g. https://mcp.example.org/mcp/emf")
	String server_url();
}
