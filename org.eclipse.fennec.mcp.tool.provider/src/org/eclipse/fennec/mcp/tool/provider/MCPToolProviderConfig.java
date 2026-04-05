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
package org.eclipse.fennec.mcp.tool.provider;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * OSGi metatype configuration for {@link MCPToolProviderImpl}.
 * Defines the tool provider's name, description, and LDAP filter
 * for selecting which {@link org.eclipse.fennec.mcp.api.MCPTool} services to aggregate.
 *
 * @author ilenia
 * @since Jan 23, 2026
 */

@ObjectClassDefinition(
		name = "MCP Tool Provider Configuration",
		description = "Configuration for the MCP Tool Provider"
		)
public @interface MCPToolProviderConfig {
	
	@AttributeDefinition(
			name = "Name",
			description = "The name of the Tool Provider",
			required = true
			)
	String name();
	
	@AttributeDefinition(
			name = "Description",
			description = "The description of the Tool Provider, which describes what kinds of tools it collects",
			required = true
			)
	String description();	
	
	@AttributeDefinition(
			name = "MCP Tool Target Filter",
			description = "The target filter for the tools injected parameters which contains the MCP tools",
			required = true
			)
	String tools_target();
	
	@AttributeDefinition(
			name = "MCP Tool Providers Cardinality Minimum",
			description = "The minimum value for the cardinality of the toolProviders reference. This is to ensure that all the MCPToolProvider required are properly injected before configuring the MCP server.",
			required = true
			)
	int tools_cardinality_minimum_int();
}
