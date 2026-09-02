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
package org.eclipse.fennec.mcp.http.component.tests;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.eclipse.fennec.mcp.api.MCPServer;
import org.eclipse.fennec.mcp.endpoint.MCPEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.servlet.runtime.dto.FailedFilterDTO;
import org.osgi.service.servlet.runtime.dto.FailedServletDTO;
import org.osgi.service.servlet.runtime.dto.FilterDTO;
import org.osgi.service.servlet.runtime.dto.RuntimeDTO;
import org.osgi.service.servlet.runtime.dto.ServletContextDTO;
import org.osgi.service.servlet.runtime.dto.ServletDTO;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.common.annotation.InjectService;
import org.osgi.test.junit5.context.BundleContextExtension;
import org.osgi.test.junit5.service.ServiceExtension;

/**
 * Guards what an activated {@code HttpMCPServerComponent} put into the HTTP Whiteboard.
 * <p>
 * {@code BundleContext.registerService} always succeeds, so an {@link MCPServer} service
 * that resolves to a live component proves nothing about reachability: a servlet the
 * whiteboard rejected - a malformed pattern, a target filter matching no runtime, a name
 * already taken - lands silently in {@code failedServletDTOs} while the component looks
 * perfectly healthy. The same holds for the two filters, and there the stake is higher:
 * {@code AbstractHttpMCPServer} registers the authentication filter before the servlet so
 * the endpoint is never reachable unguarded, and a filter whose pattern or whiteboard
 * target does not match the servlet's registers just fine and never runs - an open
 * endpoint that every service-level test passes on.
 */
@ExtendWith(BundleContextExtension.class)
@ExtendWith(ServiceExtension.class)
public class MCPEndpointWhiteboardTest extends AbstractMCPServerTest {

	private static final String GROUP = "whiteboard";
	private static final String PROVIDER_NAME = "whiteboard-test-provider";
	private static final String SERVER_NAME = "whiteboard-test-server";
	private static final String SERVLET_PATTERN = "/test/whiteboard/mcp";
	private static final String SERVER_URL = "http://localhost:8085" + SERVLET_PATTERN;

	private Configuration serverConfiguration;

	@BeforeEach
	void startServer(@InjectBundleContext BundleContext context,
			@InjectService(timeout = TIMEOUT_MS) ConfigurationAdmin cm) throws IOException {
		registerTool(context, TestMCPTool.inert("whiteboard_probe"), GROUP);
		createToolProvider(cm, "whiteboard", PROVIDER_NAME, GROUP, 1);
		Dictionary<String, Object> properties = serverProperties(SERVER_NAME, SERVLET_PATTERN, PROVIDER_NAME);
		properties.put("server.full.url", SERVER_URL);
		serverConfiguration = createServer(cm, "whiteboard", properties);
		awaitService(context, MCPServer.class, serverFilter());
	}

	@Test
	@DisplayName("the MCP servlet is registered with the whiteboard, not rejected by it")
	public void servletIsRegistered(@InjectBundleContext BundleContext context) {
		awaitCondition("The MCP servlet never appeared in the whiteboard's RuntimeDTO",
				() -> servlet(runtimeDTO(context)).isPresent());

		RuntimeDTO runtime = runtimeDTO(context);
		ServletDTO servlet = servlet(runtime).orElseThrow();
		assertThat(servlet.patterns).as("the servlet is bound to the configured pattern")
				.contains(SERVLET_PATTERN);
		assertThat(servlet.asyncSupported)
				.as("the streamable transport answers requests from an AsyncContext, so async support is mandatory")
				.isTrue();
		assertThat(Arrays.stream(runtime.failedServletDTOs).map(failed -> failed.name).toList())
				.as("a rejected servlet is invisible everywhere but here: %s",
						describeFailures(runtime.failedServletDTOs, runtime.failedFilterDTOs))
				.doesNotContain(SERVER_NAME);
	}

	@Test
	@DisplayName("the authentication and no-buffering filters guard the servlet's own pattern")
	public void filtersGuardTheSameEndpoint(@InjectBundleContext BundleContext context) {
		String authFilterName = SERVER_NAME + "-auth";
		String sseFilterName = SERVER_NAME + "-sse-no-buffering";
		awaitCondition("The MCP filters never appeared in the whiteboard's RuntimeDTO",
				() -> filter(runtimeDTO(context), authFilterName).isPresent()
						&& filter(runtimeDTO(context), sseFilterName).isPresent());

		RuntimeDTO runtime = runtimeDTO(context);
		FilterDTO authFilter = filter(runtime, authFilterName).orElseThrow();
		assertThat(authFilter.patterns)
				.as("a filter on another pattern than the servlet leaves the endpoint unguarded")
				.contains(SERVLET_PATTERN);
		assertThat(authFilter.asyncSupported).isTrue();

		FilterDTO sseFilter = filter(runtime, sseFilterName).orElseThrow();
		assertThat(sseFilter.patterns).contains(SERVLET_PATTERN);
		assertThat(sseFilter.asyncSupported)
				.as("the SSE responses are written asynchronously, so this filter must support async dispatch")
				.isTrue();

		assertThat(Arrays.stream(runtime.failedFilterDTOs).map(failed -> failed.name).toList())
				.as("a rejected filter never runs: %s",
						describeFailures(runtime.failedServletDTOs, runtime.failedFilterDTOs))
				.doesNotContain(authFilterName, sseFilterName);

		ServletContextDTO servletContext = contextOf(runtime, authFilterName);
		assertThat(Arrays.stream(servletContext.servletDTOs).map(servletDTO -> servletDTO.name).toList())
				.as("filters only run for requests the servlet in their own servlet context serves")
				.contains(SERVER_NAME);
	}

	@Test
	@DisplayName("one registration publishes both MCPServer and MCPEndpoint")
	public void oneRegistrationPublishesBothTypes(@InjectBundleContext BundleContext context) {
		ServiceReference<MCPServer> serverReference = awaitServiceReference(context, MCPServer.class, serverFilter());
		ServiceReference<MCPEndpoint> endpointReference = awaitServiceReference(context, MCPEndpoint.class,
				serverFilter());

		assertThat(endpointReference)
				.as("a consumer binding to MCPEndpoint has to be satisfied by the hosted server itself, "
						+ "not by a second registration of it")
				.isEqualTo(serverReference);
		assertThat((String[]) serverReference.getProperty(Constants.OBJECTCLASS))
				.contains(MCPServer.class.getName(), MCPEndpoint.class.getName());

		MCPEndpoint endpoint = context.getService(endpointReference);
		try {
			assertThat(endpoint.getServerName()).isEqualTo(SERVER_NAME);
			assertThat(endpoint.getServerFullUrl()).isEqualTo(SERVER_URL);
		} finally {
			context.ungetService(endpointReference);
		}
	}

	@Test
	@DisplayName("deleting the configuration takes the servlet and its filters down with it")
	public void deletingTheConfigurationUnregistersEverything(@InjectBundleContext BundleContext context)
			throws IOException {
		awaitCondition("The MCP servlet never appeared in the whiteboard's RuntimeDTO",
				() -> servlet(runtimeDTO(context)).isPresent());

		serverConfiguration.delete();

		awaitCondition("The MCPServer service outlived its configuration",
				() -> serviceReferences(context, MCPServer.class, serverFilter()).isEmpty());
		awaitCondition("The MCP servlet is still registered after deactivation - a leaked servlet "
				+ "registration would collide with the next server on that pattern",
				() -> servlet(runtimeDTO(context)).isEmpty());
		awaitCondition("An MCP filter is still registered after deactivation",
				() -> filter(runtimeDTO(context), SERVER_NAME + "-auth").isEmpty()
						&& filter(runtimeDTO(context), SERVER_NAME + "-sse-no-buffering").isEmpty());
	}

	@Test
	@DisplayName("the same server can be configured again after being deleted")
	public void theServerComesBackAfterDeletion(@InjectBundleContext BundleContext context,
			@InjectService(timeout = TIMEOUT_MS) ConfigurationAdmin cm) throws IOException {
		serverConfiguration.delete();
		awaitCondition("The MCP servlet is still registered after deactivation",
				() -> servlet(runtimeDTO(context)).isEmpty());

		Dictionary<String, Object> properties = serverProperties(SERVER_NAME, SERVLET_PATTERN, PROVIDER_NAME);
		properties.put("server.full.url", SERVER_URL);
		serverConfiguration = createServer(cm, "whiteboard-again", properties);

		MCPServer server = awaitService(context, MCPServer.class, serverFilter());
		assertThat(server.getTools()).as("a restarted server serves its provider's tools again").hasSize(1);
		awaitCondition("The MCP servlet did not come back on the same pattern - the previous "
				+ "registration was not cleaned up", () -> servlet(runtimeDTO(context)).isPresent());
	}

	private static String serverFilter() {
		return "(server.name=" + SERVER_NAME + ")";
	}

	private static Optional<ServletDTO> servlet(RuntimeDTO runtime) {
		return Arrays.stream(runtime.servletContextDTOs)
				.flatMap(servletContext -> Arrays.stream(servletContext.servletDTOs))
				.filter(servlet -> SERVER_NAME.equals(servlet.name))
				.findFirst();
	}

	private static Optional<FilterDTO> filter(RuntimeDTO runtime, String filterName) {
		return Arrays.stream(runtime.servletContextDTOs)
				.flatMap(servletContext -> Arrays.stream(servletContext.filterDTOs))
				.filter(filter -> filterName.equals(filter.name))
				.findFirst();
	}

	private static ServletContextDTO contextOf(RuntimeDTO runtime, String filterName) {
		return Arrays.stream(runtime.servletContextDTOs)
				.filter(servletContext -> Arrays.stream(servletContext.filterDTOs)
						.anyMatch(filter -> filterName.equals(filter.name)))
				.findFirst()
				.orElseThrow();
	}

	/** Renders the whiteboard's rejections with their reason codes, for a failure message. */
	private static List<String> describeFailures(FailedServletDTO[] servlets, FailedFilterDTO[] filters) {
		return Stream.concat(
				Arrays.stream(servlets).map(failed -> "servlet " + failed.name + " reason " + failed.failureReason),
				Arrays.stream(filters).map(failed -> "filter " + failed.name + " reason " + failed.failureReason))
				.toList();
	}
}
