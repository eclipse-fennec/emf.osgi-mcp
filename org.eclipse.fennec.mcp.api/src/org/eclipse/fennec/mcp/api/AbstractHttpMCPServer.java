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
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.fennec.mcp.api.auth.McpTokenVerifier;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.http.whiteboard.annotations.RequireHttpWhiteboard;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.Filter;
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
	private ServiceRegistration<?> filterRegistration;
	private ServiceRegistration<?> sseFilterRegistration;
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
	 * @return the bearer token required to access the MCP endpoint, or {@code null}
	 *         /blank if no token is configured. When blank, the authentication
	 *         filter permits loopback callers only. See {@link McpAuthenticationFilter}.
	 */
	protected abstract String getAuthToken();

	/**
	 * @return the pluggable token verifier guarding this endpoint, or {@code null}
	 *         to fall back to the static token / loopback-only behavior of
	 *         {@link McpAuthenticationFilter}. Subclasses typically wire this to an
	 *         optional {@code McpTokenVerifier} service reference.
	 */
	protected McpTokenVerifier getTokenVerifier() {
		return null;
	}

	/**
	 * @return the interval, in seconds, at which the transport sends keep-alive pings
	 *         to active sessions. A non-positive value disables keep-alive entirely.
	 *         See {@link #initializeMCPServer()} for why keep-alive is off by default.
	 */
	protected abstract long getKeepAliveIntervalSeconds();

	/**
	 * Creates the HTTP servlet transport, registers it with the OSGi HTTP Whiteboard,
	 * and builds the async MCP server with all configured capabilities, tools, prompts,
	 * and resources. Keep-alive is configurable (off by default), request timeout is 10 minutes.
	 */
	protected void initializeMCPServer() {
		HttpServletStreamableServerTransportProvider.Builder transportProviderBuilder = HttpServletStreamableServerTransportProvider.builder()
				.jsonMapper(getJsonMapper())
				.mcpEndpoint(getEndpointPath());

		// Keep-alive pings every session in the transport's session map, but the SDK only
		// supports pinging a session that holds a standalone listening (GET) SSE stream.
		// Clients that use plain request/response POST never open that stream, so each ping
		// fails with "Stream unavailable for session ..." and floods the log. Only enable
		// keep-alive when an interval is explicitly configured (> 0); a non-positive value
		// leaves it disabled (the builder treats a null interval as "no keep-alive").
		long keepAliveIntervalSeconds = getKeepAliveIntervalSeconds();
		if (keepAliveIntervalSeconds > 0) {
			transportProviderBuilder.keepAliveInterval(Duration.ofSeconds(keepAliveIntervalSeconds));
		}

		HttpServletStreamableServerTransportProvider transportProvider = transportProviderBuilder.build();

		registerHttpWhiteboard(transportProvider, context);

		mcpServer = McpServer.async(transportProvider)
				.serverInfo(getServerName(), getServerVersion())
				.jsonMapper(getJsonMapper())
				.jsonSchemaValidator(getSchemaValidator())
				.capabilities(buildCapabilities())
				.tools(getTools())
				.prompts(getPrompts())
				.resources(getResources())
				.requestTimeout(Duration.ofMinutes(10))
				.instructions(getInstructions())
				.build();
		registeredToolNames.clear();
		getTools().forEach(spec -> registeredToolNames.add(spec.tool().name()));
	}

	/**
	 * The capabilities announced in the {@code initialize} response, which is what a client
	 * consults before it calls {@code tools/list}, {@code prompts/list} or
	 * {@code resources/list} at all.
	 * <p>
	 * A capability is announced only when this server has that kind of thing to serve.
	 * Calling the SDK builder's {@code tools(..)} / {@code prompts(..)} /
	 * {@code resources(..)} always creates the capability, whatever is passed - the argument
	 * is that capability's {@code listChanged} flag, not whether to include it - so
	 * {@code hasXCapability()} has to gate the call rather than be its argument.
	 * <p>
	 * The flags passed are what this class can actually honour. {@code listChanged} promises
	 * a {@code notifications/.../list_changed} whenever the list changes, and only tools get
	 * one, from {@link #syncTools()}. Prompts and resources are static here, so they claim
	 * nothing; {@code subscribe} is likewise false, since no {@code resources/subscribe} is
	 * implemented. A subclass that starts serving either one and notifies about it should
	 * override this.
	 *
	 * @return the capabilities to announce, never {@code null}
	 */
	protected McpSchema.ServerCapabilities buildCapabilities() {
		McpSchema.ServerCapabilities.Builder capabilities = McpSchema.ServerCapabilities.builder().logging();
		if (hasToolCapability()) {
			capabilities.tools(true);
		}
		if (hasPromptCapability()) {
			capabilities.prompts(false);
		}
		if (hasResourceCapability()) {
			capabilities.resources(false, false);
		}
		return capabilities.build();
	}

	private final Set<String> registeredToolNames = ConcurrentHashMap.newKeySet();

	/**
	 * Propagates tool changes to the running MCP server. Diffs the current
	 * {@link #getTools()} against what the SDK server has registered and calls
	 * {@code addTool}/{@code removeTool}, which emit
	 * {@code notifications/tools/list_changed} to connected clients. Safe to
	 * call before initialization (no-op) and from provider change listeners.
	 */
	protected synchronized void syncTools() {
		McpAsyncServer server = mcpServer;
		if (server == null) {
			return;
		}
		Map<String, McpServerFeatures.AsyncToolSpecification> current = new LinkedHashMap<>();
		getTools().forEach(spec -> current.putIfAbsent(spec.tool().name(), spec));
		for (String name : List.copyOf(registeredToolNames)) {
			if (!current.containsKey(name)) {
				registeredToolNames.remove(name);
				server.removeTool(name).subscribe();
			}
		}
		current.forEach((name, spec) -> {
			if (registeredToolNames.add(name)) {
				server.addTool(spec).subscribe();
			}
		});
	}

	/**
	 * Registers the MCP transport provider as a Jakarta Servlet via the OSGi HTTP Whiteboard,
	 * together with an {@link McpAuthenticationFilter} guarding the same endpoint and an
	 * {@link SseNoBufferingFilter} that keeps the SSE stream from being buffered by a reverse proxy.
	 */
	protected void registerHttpWhiteboard(HttpServletStreamableServerTransportProvider transportProvider, BundleContext context) {
		Dictionary<String, Object> servletProperties = getServletProperties();
		// Register the filters before the servlet so the endpoint is never reachable
		// without its authentication guard, not even during the startup window.
		filterRegistration = context.registerService(
				Filter.class,
				new McpAuthenticationFilter(this::getAuthToken, this::getTokenVerifier),
				getFilterProperties(servletProperties)
				);
		sseFilterRegistration = context.registerService(
				Filter.class,
				new SseNoBufferingFilter(),
				getSseNoBufferingFilterProperties(servletProperties)
				);
		servletRegistration = context.registerService(
				Servlet.class,
				transportProvider,
				servletProperties
				);
	}

	/**
	 * Builds the OSGi HTTP Whiteboard properties for the authentication filter so it
	 * matches the same endpoint path and HTTP runtime as the transport servlet.
	 *
	 * @param servletProperties the servlet registration properties, used to inherit the
	 *            whiteboard target and endpoint pattern
	 * @return the filter registration properties
	 */
	protected Dictionary<String, Object> getFilterProperties(Dictionary<String, Object> servletProperties) {
		return buildFilterProperties(servletProperties, getServerName() + "-auth");
	}

	/**
	 * Builds the OSGi HTTP Whiteboard properties for the {@link SseNoBufferingFilter}. The filter is
	 * bound to the same endpoint and HTTP runtime as the transport servlet and must support async
	 * dispatch because the streamable transport serves its SSE responses asynchronously.
	 *
	 * @param servletProperties the servlet registration properties, used to inherit the
	 *            whiteboard target and endpoint pattern
	 * @return the filter registration properties
	 */
	protected Dictionary<String, Object> getSseNoBufferingFilterProperties(Dictionary<String, Object> servletProperties) {
		return buildFilterProperties(servletProperties, getServerName() + "-sse-no-buffering");
	}

	/**
	 * Builds HTTP Whiteboard filter properties bound to the transport servlet's endpoint and runtime.
	 *
	 * @param servletProperties the servlet registration properties, used to inherit the whiteboard target
	 * @param filterName the unique whiteboard filter name
	 * @return the filter registration properties
	 */
	private Dictionary<String, Object> buildFilterProperties(Dictionary<String, Object> servletProperties, String filterName) {
		Dictionary<String, Object> filterProperties = new Hashtable<>();
		filterProperties.put("osgi.http.whiteboard.filter.pattern", getEndpointPath());
		filterProperties.put("osgi.http.whiteboard.filter.name", filterName);
		filterProperties.put("osgi.http.whiteboard.filter.asyncSupported", true);
		Object target = servletProperties.get("osgi.http.whiteboard.target");
		if (target != null) {
			filterProperties.put("osgi.http.whiteboard.target", target);
		}
		return filterProperties;
	}

	/**
	 * Gracefully shuts down the MCP server and unregisters the servlet from the HTTP Whiteboard.
	 */
	protected void unregisterMCPServer() {
		if(mcpServer != null) {
			mcpServer.close();
			mcpServer = null;
		}		
		// Unregister the servlet first: while it is still reachable, the filters must
		// stay in place so no request can slip through unauthenticated during shutdown.
		if(servletRegistration != null) {
			servletRegistration.unregister();
			servletRegistration = null;
		}
		if(sseFilterRegistration != null) {
			sseFilterRegistration.unregister();
			sseFilterRegistration = null;
		}
		if(filterRegistration != null) {
			filterRegistration.unregister();
			filterRegistration = null;
		}
	}
}
