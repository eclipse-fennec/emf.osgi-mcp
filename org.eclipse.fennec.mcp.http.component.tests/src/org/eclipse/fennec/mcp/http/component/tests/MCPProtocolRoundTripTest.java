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

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Dictionary;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.eclipse.fennec.mcp.api.MCPServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.osgi.framework.BundleContext;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.common.annotation.InjectService;
import org.osgi.test.junit5.context.BundleContextExtension;
import org.osgi.test.junit5.service.ServiceExtension;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Drives the configured endpoint with a real MCP client over HTTP: initialize, list, call.
 * <p>
 * This is the only test in the bundle that proves the parts <em>compose</em>. The others
 * can pass while nothing works: the JSON mapper, the networknt schema validator, the
 * streamable HTTP transport, the whiteboard servlet and the tool provider's reactive
 * dispatch are each satisfied individually long before a client can get a tool result out
 * of the endpoint. It is also the regression net for an MCP SDK bump, since a protocol or
 * transport change shows up here and nowhere else in the workspace.
 */
@ExtendWith(BundleContextExtension.class)
@ExtendWith(ServiceExtension.class)
public class MCPProtocolRoundTripTest extends AbstractMCPServerTest {

	private static final String GROUP = "roundtrip";
	private static final String PROVIDER_NAME = "roundtrip-test-provider";
	private static final String SERVER_NAME = "roundtrip-test-server";
	private static final String SERVER_VERSION = "3.2.1";
	private static final String SERVLET_PATTERN = "/test/roundtrip/mcp";
	private static final String INSTRUCTIONS = "Call roundtrip_echo with a text argument.";

	@BeforeEach
	void startServer(@InjectBundleContext BundleContext context,
			@InjectService(timeout = TIMEOUT_MS) ConfigurationAdmin cm) throws IOException {
		registerTool(context, TestMCPTool.echo("roundtrip_echo"), GROUP);
		registerTool(context, TestMCPTool.failing("roundtrip_failing"), GROUP);
		createToolProvider(cm, "roundtrip", PROVIDER_NAME, GROUP, 2);
		Dictionary<String, Object> properties = serverProperties(SERVER_NAME, SERVLET_PATTERN, PROVIDER_NAME);
		properties.put("server.version", SERVER_VERSION);
		properties.put("server.instructions", INSTRUCTIONS);
		createServer(cm, "roundtrip", properties);
		awaitService(context, MCPServer.class, "(server.name=" + SERVER_NAME + ")");
	}

	@Test
	@DisplayName("a client initializing against the endpoint gets the configured identity back")
	public void initializeReportsTheConfiguredServer(@InjectBundleContext BundleContext context) {
		InitializeResult result = mcpClient(context, SERVLET_PATTERN, null).initialize();

		assertThat(result.serverInfo().name()).isEqualTo(SERVER_NAME);
		assertThat(result.serverInfo().version())
				.as("server.version reaches the client, so a wrong default is visible")
				.isEqualTo(SERVER_VERSION);
		assertThat(result.instructions())
				.as("server.instructions is how a deployment steers an agent; nothing else asserts it arrives")
				.isEqualTo(INSTRUCTIONS);
		assertThat(result.capabilities().tools())
				.as("has.tool.capability=true has to surface as the tools capability")
				.isNotNull();
		assertThat(result.capabilities().tools().listChanged())
				.as("the server pushes notifications/tools/list_changed, so it must announce that")
				.isTrue();
		// A capability is announced only when the server has that kind of thing to serve: a
		// client that sees prompts or resources here is entitled to call prompts/list and
		// resources/list, and this server has neither. The SDK builder creates the
		// capability whatever flag is passed to it, so leaving them off means not calling
		// it - which is exactly what a false has.*.capability has to do.
		assertThat(result.capabilities().prompts())
				.as("prompts are off, so the capability must be absent rather than announced empty")
				.isNull();
		assertThat(result.capabilities().resources())
				.as("resources are off, so the capability must be absent rather than announced empty")
				.isNull();
	}

	@Test
	@DisplayName("a server with the tool capability off does not announce tools")
	public void theToolCapabilityCanBeTurnedOff(@InjectBundleContext BundleContext context,
			@InjectService(timeout = TIMEOUT_MS) ConfigurationAdmin cm) throws IOException {
		String pattern = "/test/roundtrip/notools/mcp";
		Dictionary<String, Object> properties = serverProperties("roundtrip-notools-server", pattern, PROVIDER_NAME);
		properties.put("has.tool.capability", false);
		createServer(cm, "roundtripNoTools", properties);
		awaitService(context, MCPServer.class, "(server.name=roundtrip-notools-server)");

		InitializeResult result = mcpClient(context, pattern, null).initialize();

		// The provider is the same one, so the tools are all there to serve - the flag
		// alone decides whether a client is ever told about them.
		assertThat(result.capabilities().tools())
				.as("has.tool.capability=false has to keep the capability out of the initialize result")
				.isNull();
	}

	@Test
	@DisplayName("the whiteboard tools are listed with the schemas their MCPTool declared")
	public void toolsAreListedWithTheirSchemas(@InjectBundleContext BundleContext context) {
		McpSyncClient client = mcpClient(context, SERVLET_PATTERN, null);
		client.initialize();

		ListToolsResult tools = client.listTools();

		assertThat(tools.tools()).extracting(Tool::name)
				.containsExactlyInAnyOrder("roundtrip_echo", "roundtrip_failing");
		Tool echo = tools.tools().stream().filter(tool -> "roundtrip_echo".equals(tool.name())).findFirst()
				.orElseThrow();
		assertThat(echo.description()).isEqualTo("Echoes the text argument back");
		assertThat(echo.inputSchema())
				.as("the MCPTool's raw input schema has to survive the conversion to a Tool")
				.containsEntry("required", List.of("text"))
				.extractingByKey("properties", as(InstanceOfAssertFactories.MAP))
				.containsKey("text");
	}

	@Test
	@DisplayName("calling a tool over HTTP returns its result")
	public void callingAToolReturnsItsResult(@InjectBundleContext BundleContext context) {
		McpSyncClient client = mcpClient(context, SERVLET_PATTERN, null);
		client.initialize();

		CallToolResult result = client.callTool(
				CallToolRequest.builder("roundtrip_echo").arguments(Map.of("text", "over the wire")).build());

		assertThat(result.isError()).isFalse();
		assertThat(textOf(result)).contains("echo: over the wire");
	}

	@Test
	@DisplayName("a failing tool is reported to the client, and the session survives it")
	public void aFailingToolDoesNotBreakTheSession(@InjectBundleContext BundleContext context) {
		McpSyncClient client = mcpClient(context, SERVLET_PATTERN, null);
		client.initialize();

		// A tool whose Mono errors travels the provider's timeout and scheduler wrapper and
		// arrives as a JSON-RPC error, which the client raises: the failure is reported
		// rather than hanging until the request timeout or tearing the stream down.
		assertThatThrownBy(() -> client.callTool(CallToolRequest.builder("roundtrip_failing").build()))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("deliberate failure");

		CallToolResult next = client.callTool(
				CallToolRequest.builder("roundtrip_echo").arguments(Map.of("text", "still alive")).build());
		assertThat(textOf(next))
				.as("the session has to remain usable after a tool failure")
				.contains("echo: still alive");
	}

	/** All text content of a result, joined - the tools under test return text only. */
	private static String textOf(CallToolResult result) {
		List<String> texts = result.content().stream()
				.filter(TextContent.class::isInstance)
				.map(content -> ((TextContent) content).text())
				.toList();
		return String.join("\n", texts);
	}
}
