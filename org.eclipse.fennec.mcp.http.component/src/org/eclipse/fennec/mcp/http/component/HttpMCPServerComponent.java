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
package org.eclipse.fennec.mcp.http.component;

import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;

import org.eclipse.fennec.emf.osgi.annotation.require.RequireEMF;
import org.eclipse.fennec.mcp.api.AbstractHttpMCPServer;
import org.eclipse.fennec.mcp.api.MCPEndpoint;
import org.eclipse.fennec.mcp.api.MCPServer;
import org.eclipse.fennec.mcp.api.MCPServerConstants;
import org.eclipse.fennec.mcp.api.MCPToolProvider;
import org.eclipse.fennec.mcp.api.annotations.RequireMCPToolProvider;
import org.eclipse.fennec.mcp.api.auth.McpTokenVerifier;
import org.osgi.annotation.bundle.Capability;
import org.osgi.annotation.bundle.Requirement;
import org.osgi.annotation.bundle.Requirements;
import org.osgi.framework.BundleContext;
import org.osgi.framework.namespace.IdentityNamespace;
import org.osgi.namespace.implementation.ImplementationNamespace;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.Designate;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncPromptSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import org.osgi.service.servlet.whiteboard.annotations.RequireHttpWhiteboard;

/**
 * DS component implementing the MCP server over HTTP transport.
 * Extends {@link AbstractHttpMCPServer} and delegates all configuration
 * to {@link HttpMCPServerConfig} (OSGi metatype).
 * <p>
 * Collects tool specifications from all bound {@link MCPToolProvider} services
 * and exposes them via the MCP protocol on a configurable HTTP endpoint
 * registered through the OSGi HTTP Whiteboard.
 * <p>
 * Requires factory configuration (PID: {@code HttpMCPServerComponent}) and
 * at least one MCPToolProvider service.
 *
 * @author ilenia
 * @since Dec 2, 2025
 */
// Both types, one registration: existing consumers keep binding to MCPServer,
// and a client that only needs the address binds to MCPEndpoint and is satisfied
// by this or by a RemoteMCPEndpoint without knowing which. Service properties are
// unchanged.
@Component(name = "HttpMCPServerComponent", service = {MCPServer.class, MCPEndpoint.class}, configurationPid = "HttpMCPServerComponent", configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = HttpMCPServerConfig.class)
@Capability(namespace = ImplementationNamespace.IMPLEMENTATION_NAMESPACE, name = MCPServerConstants.MCP_WHITEBOARD_IMPLEMENTATION, version = MCPServerConstants.MCP_WHITEBOARD_VERSION)
@Requirements({
	@Requirement(namespace = IdentityNamespace.IDENTITY_NAMESPACE, name= "io.modelcontextprotocol.sdk.mcp-json-jackson3"),
	@Requirement(namespace = IdentityNamespace.IDENTITY_NAMESPACE, name= "com.ethlo.time.itu"),
	@Requirement(namespace = IdentityNamespace.IDENTITY_NAMESPACE, name= "com.networknt.json-schema-validator")
})
@RequireEMF
@RequireHttpWhiteboard
@RequireMCPToolProvider
public class HttpMCPServerComponent extends AbstractHttpMCPServer{
	
	private HttpMCPServerConfig config;
	
	@Reference
	private McpJsonMapperSupplier jsonMapper;
	@Reference
	private JsonSchemaValidatorSupplier schemaValidator;
	@Reference(name = "toolProviders", cardinality = ReferenceCardinality.AT_LEAST_ONE)
	List<MCPToolProvider> toolProviders;
	/**
	 * Optional pluggable token verification (see McpTokenVerifier); select a
	 * specific verifier per endpoint via the 'verifier.target' config property.
	 * Absent = static auth.token / loopback-only behavior.
	 */
	@Reference(name = "verifier", cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
	private volatile McpTokenVerifier tokenVerifier;

	@Activate
	public void activate(
			HttpMCPServerConfig config, 
			BundleContext context) {
		this.config = config;
		this.context = context;
		initializeMCPServer();
		// propagate dynamic tool changes (e.g. bridged ServiceClient tools) to
		// connected clients via notifications/tools/list_changed
		toolProviders.forEach(provider -> provider.onToolsChanged(this::syncTools));
	}
	
	@Deactivate
	public void deactivate() {
		unregisterMCPServer();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.eclipse.fennec.ai.mcp.api.MCPServer#getTools()
	 */
	@Override
	public List<AsyncToolSpecification> getTools() {
		return toolProviders.stream().map(p -> p.getMCPTools()).flatMap(List::stream).toList();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.eclipse.fennec.ai.mcp.api.MCPServer#getPrompts()
	 */
	@Override
	public List<AsyncPromptSpecification> getPrompts() {
		return Collections.emptyList();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.eclipse.fennec.ai.mcp.api.MCPServer#getResources()
	 */
	@Override
	public List<AsyncResourceSpecification> getResources() {
		return Collections.emptyList();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.eclipse.fennec.ai.mcp.api.AbstractHttpMCPServer#getServerName()
	 */
	@Override
	public String getServerName() {
		return config.server_name();
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.eclipse.fennec.ai.mcp.api.AbstractHttpMCPServer#getServerFullUrl()
	 */
	@Override
	public String getServerFullUrl() {
		return config.server_full_url();
	};
	
	/* 
	 * (non-Javadoc)
	 * @see org.eclipse.fennec.ai.mcp.api.AbstractHttpMCPServer#getServletProperties()
	 */
	protected Dictionary<String, Object> getServletProperties() {
		Dictionary<String, Object> servletProps = new Hashtable<>();
        servletProps.put("osgi.http.whiteboard.servlet.pattern", config.osgi_http_whiteboard_servlet_pattern());
        servletProps.put("osgi.http.whiteboard.servlet.name", config.server_name());
        servletProps.put("osgi.http.whiteboard.servlet.asyncSupported", true);
        servletProps.put("osgi.http.whiteboard.target", config.osgi_http_whiteboard_target());
		return servletProps;
	}

	
	/* 
	 * (non-Javadoc)
	 * @see org.eclipse.fennec.ai.mcp.api.AbstractHttpMCPServer#getServerVersion()
	 */
	@Override
	protected String getServerVersion() {
		return config.server_version();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.eclipse.fennec.ai.mcp.api.AbstractHttpMCPServer#hasToolCapability()
	 */
	@Override
	protected boolean hasToolCapability() {
		return config.has_tool_capability();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.eclipse.fennec.ai.mcp.api.AbstractHttpMCPServer#hasPromptCapability()
	 */
	@Override
	protected boolean hasPromptCapability() {
		return config.has_prompt_capability();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.eclipse.fennec.ai.mcp.api.AbstractHttpMCPServer#hasResourceCapability()
	 */
	@Override
	protected boolean hasResourceCapability() {
		return config.has_resource_capability();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.eclipse.fennec.ai.mcp.api.AbstractHttpMCPServer#getEndpointPath()
	 */
	@Override
	protected String getEndpointPath() {
		return config.osgi_http_whiteboard_servlet_pattern();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.eclipse.fennec.ai.mcp.api.AbstractHttpMCPServer#getInstructions()
	 */
	@Override
	protected String getInstructions() {
		return config.server_instructions();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.eclipse.fennec.mcp.api.AbstractHttpMCPServer#getSchemaValidator()
	 */
	@Override
	protected JsonSchemaValidator getSchemaValidator() {
		return schemaValidator.get();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.eclipse.fennec.mcp.api.AbstractHttpMCPServer#getJsonMapper()
	 */
	@Override
	protected McpJsonMapper getJsonMapper() {
		return jsonMapper.get();
	}

	/*
	 * (non-Javadoc)
	 * @see org.eclipse.fennec.mcp.api.AbstractHttpMCPServer#getAuthToken()
	 */
	@Override
	protected String getAuthToken() {
		return config.auth_token();
	}

	/*
	 * (non-Javadoc)
	 * @see org.eclipse.fennec.mcp.api.AbstractHttpMCPServer#getTokenVerifier()
	 */
	@Override
	protected McpTokenVerifier getTokenVerifier() {
		return tokenVerifier;
	}

	/*
	 * (non-Javadoc)
	 * @see org.eclipse.fennec.mcp.api.AbstractHttpMCPServer#getKeepAliveIntervalSeconds()
	 */
	@Override
	protected long getKeepAliveIntervalSeconds() {
		return config.keep_alive_interval_seconds();
	}
}
