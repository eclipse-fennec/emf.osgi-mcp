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
package org.eclipse.fennec.mcp.gogo.runtime;

/**
 * Constants for the Gogo MCP capability/requirement namespace.
 * Used to declare that a bundle provides or requires the Gogo shell MCP integration.
 *
 * @author mark
 * @since 05.04.2026
 */
public interface GogoMCPConstants {

	/** Implementation capability name for the Gogo MCP integration */
	public static final String MCP_GOGO_IMPLEMENTATION = "mcp.gogo";
	/** Version of the Gogo MCP capability */
	public static final String MCP_GOGO_VERSION = "1.0";

}
