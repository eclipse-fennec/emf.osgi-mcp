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
package org.eclipse.fennec.mcp.service.tools;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * Configuration of the {@link ServiceClientToolBridge}. Deny-all by default:
 * without an {@code operations.allow} entry no operation is exposed, and the
 * {@code clients.target} reference filter selects which {@code ServiceClient}
 * services are bridged at all.
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
@ObjectClassDefinition(name = "MCP Service Client Tool Bridge",
		description = "Exposes operations of selected ServiceClient services (imported SOAP/OpenAPI/gRPC documents) as MCP tools")
public @interface ServiceToolsConfig {

	@AttributeDefinition(name = "Allowed operations",
			description = "Operation name patterns to expose (exact name, or prefix ending with '*'); empty = nothing is exposed")
	String[] operations_allow() default {};

	@AttributeDefinition(name = "Tool name prefix",
			description = "Overrides the client-name prefix of the generated tool names; empty = use the client's 'name' service property (fallback: config instance name)")
	String tools_prefix() default "";
}
