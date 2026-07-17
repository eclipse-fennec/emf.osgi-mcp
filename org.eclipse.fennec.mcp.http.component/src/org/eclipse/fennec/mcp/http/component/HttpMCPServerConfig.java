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
package org.eclipse.fennec.mcp.http.component;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * OSGi metatype configuration for {@link HttpMCPServerComponent}.
 * Controls server identity, HTTP endpoint, MCP capability toggles
 * (tools/prompts/resources), and tool provider selection via LDAP filter.
 *
 * @author ilenia
 * @since Dec 2, 2025
 */
@ObjectClassDefinition(
		name = "MCP Http Server Component Configuration",
		description = "Configuration for the MCP Server with Http transport"
		)
public @interface HttpMCPServerConfig {

	@AttributeDefinition(
			name = "Server Name",
			description = "The name of the MCP server",
			required = false
			)
	String server_name() default "my-mcp-server";
	
	@AttributeDefinition(
			name = "Server Full URL",
			description = "The complete URL of the MCP server",
			required = true
			)
	String server_full_url();

	@AttributeDefinition(
			name = "Server Version",
			description = "The version of the MCP server",
			required = false
			)
	String server_version() default "1.0.0";

	@AttributeDefinition(
			name = "Servlet Pattern",
			description = "The servlet URL pattern (e.g., /mcp/message). This is used to configure the Servlet and also as endpoint for the MCP",
			required = true
			)
	String osgi_http_whiteboard_servlet_pattern();

	@AttributeDefinition(
			name = "Whiteboard Target",
			description = "LDAP filter to target a specific HTTP Whiteboard runtime",
			required = true
			)
	String osgi_http_whiteboard_target();
	
	@AttributeDefinition(
			name = "MCP Tool Capability",
			description = "Whether or not to expose tools from the MCP server",
			defaultValue = "false"
			)
	boolean has_tool_capability() default false;
	
	@AttributeDefinition(
			name = "MCP Resource Capability",
			description = "Whether or not to expose resources from the MCP server",
			defaultValue = "false"
			)
	boolean has_resource_capability() default false;
	
	@AttributeDefinition(
			name = "MCP Prompt Capability",
			description = "Whether or not to expose prompts from the MCP server",
			defaultValue = "false"
			)
	boolean has_prompt_capability() default false;
	
	@AttributeDefinition(
			name = "MCP Server Instructions",
			description = "The optional instructions to provide to the client to interact with the MCP server.",
			required = false
			)
	String server_instructions();

	@AttributeDefinition(
			name = "Authentication Token",
			description = "Bearer token required in the 'Authorization: Bearer <token>' header to access the MCP endpoint. "
					+ "If left empty, only loopback (localhost) callers are permitted and any remote request is rejected. "
					+ "Set a strong token before exposing the endpoint beyond localhost.",
			type = AttributeType.PASSWORD,
			required = false
			)
	String auth_token() default "";
	
	@AttributeDefinition(
			name = "MCP Tool Providers Target Filter",
			description = "The target filter for the toolProviders injected parameters which contains the providers of MCP tool",
			defaultValue = "(component.name=EmptyMCPToolProvider)"
			)
	String toolProviders_target() default "(component.name=EmptyMCPToolProvider)";
	
	@AttributeDefinition(
			name = "MCP Tool Providers Cardinality Minimum",
			description = "The minimum value for the cardinality of the toolProviders reference. This is to ensure that all the MCPToolProvider required are properly injected before configuring the MCP server.",
			defaultValue = "(component.name=EmptyMCPToolProvider)"
			)
	int toolProviders_cardinality_minimum_int() default 1;


}
