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
 * One active MCP server.
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
public class MCPServerDTO extends DTO {

	/** The server name used for MCP identification. */
	public String name;

	/** The complete URL at which the server is reachable. */
	public String url;

	/** Number of tool specifications the server currently serves. */
	public int toolCount;

	/** Number of prompt specifications the server currently serves. */
	public int promptCount;

	/** Number of resource specifications the server currently serves. */
	public int resourceCount;
}
