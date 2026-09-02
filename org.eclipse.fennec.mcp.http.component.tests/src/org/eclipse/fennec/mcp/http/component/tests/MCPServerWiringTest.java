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
import java.util.Dictionary;

import org.eclipse.fennec.mcp.api.MCPServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.osgi.framework.BundleContext;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.common.annotation.InjectService;
import org.osgi.test.junit5.context.BundleContextExtension;
import org.osgi.test.junit5.service.ServiceExtension;

/**
 * The two DS knobs the runtime configurations rely on, seen from outside the component.
 * <ul>
 * <li>{@code toolProviders.cardinality.minimum} is what makes a partially deployed feature
 * safe: {@code emf.runtime.config} asks for two providers and {@code inference.config} for
 * more, on the assumption that the server stays unsatisfied - not half-configured - until
 * they are all there. Nothing tested that the knob really gates activation.</li>
 * <li>One framework hosting several endpoints is the shape {@code inference.runtime} ships,
 * which is why its {@code MCPServer} reference is targeted by {@code server.name}. Two
 * servers must therefore coexist as separately addressable services - and their whiteboard
 * registrations, whose filter names are derived from the server name, must not collide.</li>
 * </ul>
 */
@ExtendWith(BundleContextExtension.class)
@ExtendWith(ServiceExtension.class)
public class MCPServerWiringTest extends AbstractMCPServerTest {

	private static final String GROUP = "wiring";

	/** Long enough to catch a server that activates when it should not, short enough to pay for. */
	private static final long SETTLE_MS = 1_500L;

	@Test
	@DisplayName("a server stays unsatisfied until its minimum number of tool providers is there")
	public void theProviderMinimumGatesActivation(@InjectBundleContext BundleContext context,
			@InjectService(timeout = TIMEOUT_MS) ConfigurationAdmin cm) throws Exception {
		String serverName = "gated-test-server";
		String filter = "(server.name=" + serverName + ")";
		// One tool each: two providers contributing the same tool would hand the server two
		// specifications of one name, which the SDK rejects for reasons of its own.
		registerTool(context, TestMCPTool.inert("gated_first"), GROUP + "-a");
		registerTool(context, TestMCPTool.inert("gated_second"), GROUP + "-b");
		createToolProvider(cm, "gatedA", "gated-provider-a", GROUP + "-a", 1);

		Dictionary<String, Object> properties = serverProperties(serverName, "/test/gated/mcp", "ignored");
		properties.put("toolProviders.target", "(name=gated-provider-*)");
		properties.put("toolProviders.cardinality.minimum", 2);
		createServer(cm, "gated", properties);

		Thread.sleep(SETTLE_MS);
		assertThat(serviceReferences(context, MCPServer.class, filter))
				.as("one of the two required tool providers is missing, so the server must not "
						+ "come up serving half of what it was configured for")
				.isEmpty();

		createToolProvider(cm, "gatedB", "gated-provider-b", GROUP + "-b", 1);

		MCPServer server = awaitService(context, MCPServer.class, filter);
		assertThat(server.getTools())
				.as("both providers contribute the one tool they match")
				.hasSize(2);
	}

	@Test
	@DisplayName("two servers coexist as separately addressable endpoints")
	public void twoServersCoexist(@InjectBundleContext BundleContext context,
			@InjectService(timeout = TIMEOUT_MS) ConfigurationAdmin cm) throws IOException {
		registerTool(context, TestMCPTool.echo("wiring_echo"), GROUP);
		createToolProvider(cm, "pair", "pair-test-provider", GROUP, 1);
		String firstPattern = "/test/pair/first/mcp";
		String secondPattern = "/test/pair/second/mcp";
		createServer(cm, "pairFirst", serverProperties("pair-first-server", firstPattern, "pair-test-provider"));
		createServer(cm, "pairSecond", serverProperties("pair-second-server", secondPattern, "pair-test-provider"));

		MCPServer first = awaitService(context, MCPServer.class, "(server.name=pair-first-server)");
		MCPServer second = awaitService(context, MCPServer.class, "(server.name=pair-second-server)");
		assertThat(first).isNotSameAs(second);

		// Each endpoint has to be reachable in its own right: the servlets, and the
		// filters whose whiteboard names are derived from the server name, must all have
		// been accepted rather than one shadowing the other.
		assertThat(mcpClient(context, firstPattern, null).initialize().serverInfo().name())
				.isEqualTo("pair-first-server");
		assertThat(mcpClient(context, secondPattern, null).initialize().serverInfo().name())
				.isEqualTo("pair-second-server");
	}
}
