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
import java.util.List;

import org.eclipse.fennec.mcp.api.MCPServer;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.common.annotation.InjectService;
import org.osgi.test.junit5.context.BundleContextExtension;
import org.osgi.test.junit5.service.ServiceExtension;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * A tool registered after the server is up has to become callable on the live session.
 * <p>
 * The path is three bundles long: the {@code MCPTool} service arrives at
 * {@code MCPToolProviderImpl}'s dynamic reference, which calls the listener
 * {@code HttpMCPServerComponent} installed on activation, which runs
 * {@code AbstractHttpMCPServer#syncTools()}, which diffs and pushes into the SDK server.
 * A break anywhere along it looks like an MCP server that serves whatever tools happened
 * to exist at activation - correct at startup, quietly stale afterwards - which is exactly
 * what the bridged {@code ServiceClient} tools depend on not happening. Asserting through
 * a client's {@code tools/list} rather than {@code MCPServer#getTools()} is the point: the
 * latter recomputes from the providers on every call and is green even when nothing was
 * ever pushed into the running server.
 */
@ExtendWith(BundleContextExtension.class)
@ExtendWith(ServiceExtension.class)
public class MCPToolChangesTest extends AbstractMCPServerTest {

	private static final String GROUP = "changes";
	private static final String PROVIDER_NAME = "changes-test-provider";
	private static final String SERVER_NAME = "changes-test-server";
	private static final String SERVLET_PATTERN = "/test/changes/mcp";

	private MCPServer server;

	@BeforeEach
	void startServer(@InjectBundleContext BundleContext context,
			@InjectService(timeout = TIMEOUT_MS) ConfigurationAdmin cm) throws IOException {
		registerTool(context, TestMCPTool.echo("changes_first"), GROUP);
		createToolProvider(cm, "changes", PROVIDER_NAME, GROUP, 1);
		createServer(cm, "changes", serverProperties(SERVER_NAME, SERVLET_PATTERN, PROVIDER_NAME));
		server = awaitService(context, MCPServer.class, "(server.name=" + SERVER_NAME + ")");
	}

	@Test
	@DisplayName("a tool registered after startup shows up on a running session")
	public void aLateToolBecomesVisibleAndCallable(@InjectBundleContext BundleContext context) {
		McpSyncClient client = mcpClient(context, SERVLET_PATTERN, null);
		client.initialize();
		assertThat(toolNames(client)).containsExactly("changes_first");

		registerTool(context, TestMCPTool.echo("changes_second"), GROUP);

		awaitCondition("The late tool never reached the running MCP server - the provider's change "
				+ "listener or syncTools() is not wired", () -> toolNames(client).contains("changes_second"));
		assertThat(server.getTools()).hasSize(2);
	}

	@Test
	@DisplayName("an unregistered tool disappears from a running session")
	public void anUnregisteredToolDisappears(@InjectBundleContext BundleContext context) {
		ServiceRegistration<MCPTool> late = registerTool(context, TestMCPTool.echo("changes_transient"), GROUP);
		McpSyncClient client = mcpClient(context, SERVLET_PATTERN, null);
		client.initialize();
		awaitCondition("The second tool never showed up in the first place",
				() -> toolNames(client).contains("changes_transient"));

		late.unregister();
		forget(late);

		awaitCondition("The removed tool is still advertised by the running MCP server - syncTools() "
				+ "never called removeTool", () -> !toolNames(client).contains("changes_transient"));
		assertThat(toolNames(client)).containsExactly("changes_first");
	}

	@Test
	@DisplayName("two servers on one provider are both told about a late tool")
	public void everyServerOnAProviderSeesTheChange(@InjectBundleContext BundleContext context,
			@InjectService(timeout = TIMEOUT_MS) ConfigurationAdmin cm) throws IOException {
		// One provider, two servers - the shape a runtime grows into by adding an endpoint
		// over tools it already serves. A provider that kept only the last listener would
		// leave this first server serving its activation-time tool list for good, with
		// nothing anywhere to show it: getTools() recomputes and stays green, and the
		// second server keeps working.
		String secondPattern = "/test/changes/second/mcp";
		createServer(cm, "changesSecond", serverProperties("changes-second-server", secondPattern, PROVIDER_NAME));
		awaitService(context, MCPServer.class, "(server.name=changes-second-server)");

		McpSyncClient onFirst = mcpClient(context, SERVLET_PATTERN, null);
		onFirst.initialize();
		McpSyncClient onSecond = mcpClient(context, secondPattern, null);
		onSecond.initialize();

		registerTool(context, TestMCPTool.echo("changes_for_both"), GROUP);

		awaitCondition("The server that bound the provider last never saw the late tool",
				() -> toolNames(onSecond).contains("changes_for_both"));
		awaitCondition("The server that bound the provider first never saw the late tool - its "
				+ "change listener was replaced by the second server's",
				() -> toolNames(onFirst).contains("changes_for_both"));
	}

	private static List<String> toolNames(McpSyncClient client) {
		return client.listTools().tools().stream().map(Tool::name).toList();
	}
}
