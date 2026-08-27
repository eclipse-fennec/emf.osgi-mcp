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

import java.lang.annotation.Annotation;

import org.eclipse.fennec.mcp.api.MCPServer;
import org.junit.jupiter.api.Test;

/**
 * The endpoint/server split: a remote MCP server is addressable without anything
 * being hosted here, and a hosted server is still addressable the same way.
 *
 * @author ilenia
 * @since Aug 27, 2026
 */
class RemoteMCPEndpointTest {

	private static final String NAME = "remote-emf-mcp-server";
	private static final String URL = "https://mcp.example.org/mcp/emf";

	/** The OCD is an annotation type, so a test value is an implementation of it. */
	private static RemoteMCPEndpointConfig config(String serverName, String serverUrl) {
		return new RemoteMCPEndpointConfig() {

			@Override
			public Class<? extends Annotation> annotationType() {
				return RemoteMCPEndpointConfig.class;
			}

			@Override
			public String server_name() {
				return serverName;
			}

			@Override
			public String server_url() {
				return serverUrl;
			}
		};
	}

	@Test
	void aHostedServerIsAlsoAnEndpoint() {
		// The split has to be source-compatible for everything already binding to
		// MCPServer, which is exactly this relation.
		assertThat(MCPEndpoint.class).isAssignableFrom(MCPServer.class);
	}

	@Test
	void anEndpointNeedsNoServerToBeReachable() {
		// The point of the split: nothing here implements MCPServer, so a client can
		// be satisfied without a local HttpMCPServerComponent deployment.
		RemoteMCPEndpoint endpoint = new RemoteMCPEndpoint();
		endpoint.activate(config(NAME, URL));

		assertThat(endpoint).isInstanceOf(MCPEndpoint.class);
		assertThat(endpoint).isNotInstanceOf(MCPServer.class);
	}

	@Test
	void configurationIsTheWholeImplementation() {
		RemoteMCPEndpoint endpoint = new RemoteMCPEndpoint();
		endpoint.activate(config(NAME, URL));

		assertThat(endpoint.getServerName()).isEqualTo(NAME);
		assertThat(endpoint.getServerFullUrl()).isEqualTo(URL);
	}

	@Test
	void aReconfiguredEndpointMovesWithoutBeingRecreated() {
		// @Modified, so retargeting a client at another host does not churn the
		// service registration every consumer is bound to.
		RemoteMCPEndpoint endpoint = new RemoteMCPEndpoint();
		endpoint.activate(config(NAME, URL));

		endpoint.activate(config("other-server", "https://elsewhere.example/mcp"));

		assertThat(endpoint.getServerName()).isEqualTo("other-server");
		assertThat(endpoint.getServerFullUrl()).isEqualTo("https://elsewhere.example/mcp");
	}

	@Test
	void aRemoteUrlIsTakenAsGivenAndNeverProbed() {
		// An endpoint asserts an address; it does not verify one. An unreachable host
		// must still activate, or wiring a client ahead of its server would fail.
		RemoteMCPEndpoint endpoint = new RemoteMCPEndpoint();
		endpoint.activate(config(NAME, "https://host.invalid:9/mcp"));

		assertThat(endpoint.getServerFullUrl()).isEqualTo("https://host.invalid:9/mcp");
	}
}
