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

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;

/**
 * An MCP server hosted somewhere else, published as a service so a client in
 * this framework can address it.
 * <p>
 * Configuration is the whole implementation. There is no transport here, no
 * servlet, no tool aggregation and no connection attempt at activation — an
 * endpoint asserts an address, it does not verify one. Whether the remote server
 * answers is the client's business, and failing activation because a remote host
 * happened to be down at startup would make the wiring less useful, not safer.
 * <p>
 * Deliberately <b>not</b> an {@link org.eclipse.fennec.mcp.api.MCPServer}: this
 * cannot enumerate the remote server's tools, prompts or resources, and claiming
 * that interface would mean returning empty lists that read as "the server has
 * no tools" rather than "ask the server yourself".
 *
 * @author ilenia
 * @since Aug 27, 2026
 */
@Component(name = "RemoteMCPEndpoint", service = MCPEndpoint.class, configurationPid = "RemoteMCPEndpoint", configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = RemoteMCPEndpointConfig.class, factory = true)
public class RemoteMCPEndpoint implements MCPEndpoint {

	private volatile String serverName;
	private volatile String serverFullUrl;

	/** DS constructor. */
	public RemoteMCPEndpoint() {
	}

	/** Test constructor. */
	RemoteMCPEndpoint(String serverName, String serverFullUrl) {
		this.serverName = serverName;
		this.serverFullUrl = serverFullUrl;
	}

	@Activate
	@Modified
	void activate(RemoteMCPEndpointConfig config) {
		this.serverName = config.server_name();
		this.serverFullUrl = config.server_url();
	}

	@Override
	public String getServerName() {
		return serverName;
	}

	@Override
	public String getServerFullUrl() {
		return serverFullUrl;
	}
}
