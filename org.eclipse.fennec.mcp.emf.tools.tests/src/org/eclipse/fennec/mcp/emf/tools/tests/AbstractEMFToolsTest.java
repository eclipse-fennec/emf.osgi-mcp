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
package org.eclipse.fennec.mcp.emf.tools.tests;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import org.eclipse.fennec.mcp.api.MCPTool;
import org.junit.jupiter.api.AfterEach;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Shared plumbing for the EMF model tools under OSGi: configures the guard the way a
 * deployment does, finds a tool by its {@code tool.name} service property, and calls it.
 * <p>
 * Everything here goes through services and ConfigAdmin rather than constructors, because
 * the DS wiring is the part the unit tests cannot reach - {@code ModelGuard} is
 * {@code configurationPolicy = REQUIRE} and carries an <em>optional</em>
 * {@code MetadataService} reference, and neither fact is visible from Java.
 */
abstract class AbstractEMFToolsTest {

	/** Generous: SCR and ConfigAdmin both have to settle first. */
	protected static final long TIMEOUT_MS = 10_000L;

	private static final long POLL_INTERVAL_MS = 50L;

	private final List<Configuration> configurations = new ArrayList<>();

	@AfterEach
	void deleteConfigurations() throws IOException {
		for (int i = configurations.size() - 1; i >= 0; i--) {
			try {
				configurations.get(i).delete();
			} catch (IllegalStateException alreadyGone) {
				// deleted by the test itself
			}
		}
		configurations.clear();
	}

	/**
	 * Configures {@code EMFModelGuard}, whose configuration policy is REQUIRE: without
	 * this no guard exists and no EMF tool activates at all.
	 */
	protected void configureGuard(ConfigurationAdmin cm, String packageAllowList, String classAllowList)
			throws IOException {
		Dictionary<String, Object> properties = new Hashtable<>();
		properties.put("epackage.allowlist", new String[] { packageAllowList });
		properties.put("eclass.allowlist", new String[] { classAllowList });
		Configuration configuration = cm.getConfiguration("EMFModelGuard", "?");
		configurations.add(configuration);
		configuration.update(properties);
	}

	/**
	 * Waits for the {@link MCPTool} carrying this {@code tool.name}. Its presence is the
	 * assertion that matters on its own: the tool components have a mandatory reference to
	 * {@code ModelGuard}, so a guard that failed to activate shows up here as a tool that
	 * never appears.
	 */
	protected MCPTool awaitTool(BundleContext context, String toolName) {
		String filter = String.format("(tool.name=%s)", toolName);
		long deadline = System.currentTimeMillis() + TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline) {
			try {
				Collection<ServiceReference<MCPTool>> references = context.getServiceReferences(MCPTool.class, filter);
				if (!references.isEmpty()) {
					MCPTool tool = context.getService(references.iterator().next());
					if (tool != null) {
						return tool;
					}
				}
			} catch (InvalidSyntaxException e) {
				throw new IllegalStateException(e);
			}
			sleep();
		}
		return fail("No MCPTool with tool.name=" + toolName + " appeared within " + TIMEOUT_MS
				+ "ms. The tool components require a ModelGuard, so this is what a guard that never "
				+ "activated looks like from outside.");
	}

	/** Calls a tool and returns its text payload, asserting it did not report an error. */
	protected String call(MCPTool tool, Map<String, Object> arguments) {
		McpSchema.CallToolResult result = invoke(tool, arguments);
		String text = text(result);
		if (Boolean.TRUE.equals(result.isError())) {
			fail("tool reported an error: " + text);
		}
		return text;
	}

	/** Calls a tool expecting a refusal, and returns the message. */
	protected String callExpectingError(MCPTool tool, Map<String, Object> arguments) {
		McpSchema.CallToolResult result = invoke(tool, arguments);
		String text = text(result);
		if (!Boolean.TRUE.equals(result.isError())) {
			fail("expected the call to be refused, but it answered: " + text);
		}
		return text;
	}

	private McpSchema.CallToolResult invoke(MCPTool tool, Map<String, Object> arguments) {
		McpSchema.CallToolResult result = tool.execute(null, arguments).block();
		assertNotNull(result, "the tool returned no result at all");
		return result;
	}

	private static String text(McpSchema.CallToolResult result) {
		return ((McpSchema.TextContent) result.content().get(0)).text();
	}

	private static void sleep() {
		try {
			Thread.sleep(POLL_INTERVAL_MS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
