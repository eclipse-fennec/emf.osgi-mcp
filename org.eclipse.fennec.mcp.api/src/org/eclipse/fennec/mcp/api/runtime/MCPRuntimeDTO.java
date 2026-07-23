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
package org.eclipse.fennec.mcp.api.runtime;

import org.osgi.dto.DTO;

/**
 * Snapshot of the complete MCP whiteboard state.
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
public class MCPRuntimeDTO extends DTO {

	/** The active MCP servers, sorted by server name. */
	public MCPServerDTO[] servers;

	/** The tool providers with the tools they matched, sorted by name. */
	public MCPToolProviderDTO[] toolProviders;

	/** All MCP tool services currently registered, sorted by tool name. */
	public MCPToolDTO[] tools;
}
