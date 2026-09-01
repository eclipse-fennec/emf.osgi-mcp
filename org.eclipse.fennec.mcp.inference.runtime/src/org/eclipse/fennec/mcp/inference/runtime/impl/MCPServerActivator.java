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
package org.eclipse.fennec.mcp.inference.runtime.impl;

import org.eclipse.fennec.mcp.api.MCPServer;
import org.eclipse.fennec.mcp.api.annotations.RequireMCPServer;
import org.eclipse.fennec.mcp.inference.runtime.InferenceMCPConstants;
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
 * Immediate DS component that triggers the metamodel-inference MCP server
 * activation chain, and the resolution anchor for the inference feature.
 * <p>
 * The requirements below are the whole point of this bundle: one
 * {@code -runrequires} on {@code osgi.implementation=mcp.inference} pulls in the
 * EMF and metadata tools, the model.atlas publishing tool and its provider
 * config, and the inference configuration — so the 21-tool minimum on
 * {@code MCPToolProvider~inference} is guaranteed by the resolve rather than
 * left to whoever assembles the runtime.
 * <p>
 * The {@link MCPServer} reference is targeted at the inference server by name;
 * a runtime that also hosts {@code /mcp/emf} publishes more than one, and
 * binding an arbitrary one would log readiness for the wrong endpoint.
 */
@Component
@Capability(namespace = ImplementationNamespace.IMPLEMENTATION_NAMESPACE, name = InferenceMCPConstants.MCP_INFERENCE_IMPLEMENTATION, version = InferenceMCPConstants.MCP_INFERENCE_VERSION)
@Requirements({
	@Requirement(namespace = IdentityNamespace.IDENTITY_NAMESPACE, name = "org.eclipse.fennec.mcp.emf.tools"),
	@Requirement(namespace = IdentityNamespace.IDENTITY_NAMESPACE, name = "org.eclipse.fennec.mcp.metadata.tools"),
	@Requirement(namespace = IdentityNamespace.IDENTITY_NAMESPACE, name = "org.eclipse.fennec.mcp.inference.runtime"),
	@Requirement(namespace = IdentityNamespace.IDENTITY_NAMESPACE, name = "org.eclipse.fennec.mcp.inference.config"),
	@Requirement(namespace = IdentityNamespace.IDENTITY_NAMESPACE, name = "org.eclipse.fennec.model.atlas.mcp.tools"),
	@Requirement(namespace = IdentityNamespace.IDENTITY_NAMESPACE, name = "org.eclipse.fennec.model.atlas.mcp.config")
})
@RequireMCPServer
public class MCPServerActivator {

	private static final Logger LOG = LoggerFactory.getLogger(MCPServerActivator.class);

	@Reference(target = "(server.name=" + InferenceMCPConstants.INFERENCE_SERVER_NAME + ")")
	private MCPServer mcpServer;

	@Activate
	void activate() {
		LOG.info("Metamodel inference MCP Server activated: {}", mcpServer);
	}
}
