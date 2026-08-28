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

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.fennec.mcp.endpoint.MCPEndpoint;
import org.eclipse.fennec.mcp.endpoint.RemoteMCPEndpoint;
import org.junit.jupiter.api.Test;

/**
 * The seam between the two bundles, asserted from the only layer that can see
 * both: {@code mcp.endpoint} carries the client half and depends on OSGi alone,
 * {@code mcp.api} carries the server half and depends on the MCP SDK.
 *
 * @author ilenia
 * @since Aug 28, 2026
 */
class EndpointServerSplitTest {

	@Test
	void aHostedServerIsAlsoAnEndpoint() {
		// The split has to stay source-compatible for everything binding to
		// MCPServer, which is exactly this relation — across the bundle boundary.
		assertThat(MCPEndpoint.class).isAssignableFrom(MCPServer.class);
	}

	@Test
	void aRemoteEndpointIsNeverAServer() {
		// It cannot enumerate the remote server's tools, so claiming MCPServer would
		// mean answering "no tools" to a question it cannot answer at all.
		assertThat(MCPServer.class.isAssignableFrom(RemoteMCPEndpoint.class)).isFalse();
	}
}
