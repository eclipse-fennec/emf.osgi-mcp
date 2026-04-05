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

/**
 * Constants for the MCP whiteboard capability/requirement namespace.
 * Used in {@code @Capability} and {@code @Requirement} annotations to wire
 * MCP server and tool provider implementations via OSGi resolver.
 *
 * @author mark
 * @since 05.04.2026
 */
public interface MCPServerConstants {

	/** Implementation capability name for MCP server providers */
	public static final String MCP_WHITEBOARD_IMPLEMENTATION = "mcp.server";
	/** Version of the MCP server capability */
	public static final String MCP_WHITEBOARD_VERSION = "1.0";

	/** Implementation capability name for MCP tool provider services */
	public static final String MCP_TOOL_PROVIDER_IMPLEMENTATION = "mcp.toolprovider";
	/** Version of the MCP tool provider capability */
	public static final String MCP_TOOL_PROVIDER_VERSION = "1.0";

}
