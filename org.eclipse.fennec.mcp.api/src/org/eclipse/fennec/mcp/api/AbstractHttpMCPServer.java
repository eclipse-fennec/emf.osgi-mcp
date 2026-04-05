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

import java.time.Duration;
import java.util.Dictionary;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.http.whiteboard.annotations.RequireHttpWhiteboard;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.Servlet;

/**
 * Abstract base for HTTP-based MCP server implementations. Handles the lifecycle
 * of the MCP async server and its HTTP servlet transport registration via the
 * OSGi HTTP Whiteboard.
 * <p>
 * Subclasses must provide configuration (server name, version, capabilities, etc.)
 * through abstract template methods, and call {@link #initializeMCPServer()} on
 * activation and {@link #unregisterMCPServer()} on deactivation.
 *
 * @author ilenia
 * @since Dec 2, 2025
 */
@RequireHttpWhiteboard
public abstract class AbstractHttpMCPServer implements MCPServer {

	private ServiceRegistration<?> servletRegistration;
	protected McpAsyncServer mcpServer;
	protected BundleContext context;

	/** @return OSGi HTTP Whiteboard servlet properties (pattern, name, async support, target filter) */
	protected abstract Dictionary<String, Object> getServletProperties();

	/** @return the MCP server version string reported to clients */
	protected abstract String getServerVersion();

	/** @return the servlet endpoint path used for both HTTP Whiteboard and MCP transport */
	protected abstract String getEndpointPath();

	/** @return {@code true} if this server advertises tool capability to clients */
	protected abstract boolean hasToolCapability();

	/** @return {@code true} if this server advertises prompt capability to clients */
	protected abstract boolean hasPromptCapability();

	/** @return {@code true} if this server advertises resource capability to clients */
	protected abstract boolean hasResourceCapability();

	/** @return optional human-readable instructions for clients on how to interact with this server */
	protected abstract String getInstructions();

	/** @return the JSON schema validator used to validate tool input/output schemas */
	protected abstract JsonSchemaValidator getSchemaValidator();

	/** @return the JSON mapper used for MCP protocol serialization */
	protected abstract McpJsonMapper getJsonMapper();

	/**
	 * Creates the HTTP servlet transport, registers it with the OSGi HTTP Whiteboard,
	 * and builds the async MCP server with all configured capabilities, tools, prompts,
	 * and resources. Keep-alive interval is 1 second, request timeout is 10 minutes.
	 */
	protected void initializeMCPServer() {
		HttpServletStreamableServerTransportProvider transportProvider = HttpServletStreamableServerTransportProvider.builder()
				.jsonMapper(getJsonMapper())
				.mcpEndpoint(getEndpointPath())
				.keepAliveInterval(Duration.ofMillis(1000))
				.build();

		registerHttpWhiteboard(transportProvider, context);

		mcpServer = McpServer.async(transportProvider)
				.serverInfo(getServerName(), getServerVersion())
				.jsonMapper(getJsonMapper())
				.jsonSchemaValidator(getSchemaValidator())
				.capabilities(McpSchema.ServerCapabilities.builder()
						.tools(hasToolCapability())
						.resources(hasResourceCapability(), hasResourceCapability())
						.prompts(hasPromptCapability())
						.logging()
						.build())
				.tools(getTools())
				.prompts(getPrompts())
				.resources(getResources())
				.requestTimeout(Duration.ofMinutes(10))
				.instructions(getInstructions())
				.build();
		
	}

	/**
	 * Registers the MCP transport provider as a Jakarta Servlet via the OSGi HTTP Whiteboard.
	 */
	protected void registerHttpWhiteboard(HttpServletStreamableServerTransportProvider transportProvider, BundleContext context) {
		servletRegistration = context.registerService(
				Servlet.class,
				transportProvider,
				getServletProperties()
				);
	}

	/**
	 * Gracefully shuts down the MCP server and unregisters the servlet from the HTTP Whiteboard.
	 */
	protected void unregisterMCPServer() {
		if(mcpServer != null) {
			mcpServer.close();
			mcpServer = null;
		}		
		if(servletRegistration != null) {
			servletRegistration.unregister();
			servletRegistration = null;
		}
	}
}
