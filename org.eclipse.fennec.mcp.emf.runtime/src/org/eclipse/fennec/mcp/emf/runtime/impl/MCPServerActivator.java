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
package org.eclipse.fennec.mcp.emf.runtime.impl;

import org.eclipse.fennec.mcp.api.MCPServer;
import org.eclipse.fennec.mcp.api.annotations.RequireMCPServer;
import org.eclipse.fennec.mcp.emf.runtime.EmfMCPConstants;
import org.osgi.annotation.bundle.Capability;
import org.osgi.annotation.bundle.Requirement;
import org.osgi.annotation.bundle.Requirements;
import org.osgi.framework.namespace.IdentityNamespace;
import org.osgi.namespace.implementation.ImplementationNamespace;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immediate DS component that triggers the EMF metamodel MCP server activation
 * chain. By referencing {@link MCPServer} it ensures the whole dependency graph
 * (tools, tool provider, HTTP server) is resolved and activated before logging
 * readiness. The capability/requirement annotations pull in the EMF tools
 * bundle and this runtime bundle.
 */
@Component
@Capability(namespace = ImplementationNamespace.IMPLEMENTATION_NAMESPACE, name = EmfMCPConstants.MCP_EMF_IMPLEMENTATION, version = EmfMCPConstants.MCP_EMF_VERSION)
@Requirements({
	@Requirement(namespace = IdentityNamespace.IDENTITY_NAMESPACE, name = "org.eclipse.fennec.mcp.emf.tools"),
	@Requirement(namespace = IdentityNamespace.IDENTITY_NAMESPACE, name = "org.eclipse.fennec.mcp.emf.runtime")
})
@RequireMCPServer
public class MCPServerActivator {

	private static final Logger LOG = LoggerFactory.getLogger(MCPServerActivator.class);

	@Reference
	private MCPServer mcpServer;

	@Activate
	void activate() {
		LOG.info("EMF metamodel MCP Server activated: {}", mcpServer);
	}
}
