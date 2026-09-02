/**
 * Copyright (c) 2012 - 2026 Data In Motion and others.
 * All rights reserved.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.eclipse.fennec.mcp.http.component.tests;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;

import org.eclipse.fennec.mcp.api.MCPServer;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.junit5.context.BundleContextExtension;

import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * Guards {@code HttpMCPServerComponent} against the two ways it can silently fail to come
 * up under OSGi. Both are invisible to a bnd resolve: {@code -resolve.effective} skips
 * {@code osgi.service}, so a missing service provider or a component that throws in
 * {@code @Activate} only shows up when a framework is actually running.
 * <ul>
 * <li>The MCP SDK publishes {@code McpJsonMapperSupplier} /
 * {@code JsonSchemaValidatorSupplier} through {@code META-INF/services} only. If nothing
 * turns those into OSGi services, the mandatory references of
 * {@code HttpMCPServerComponent} are never satisfied and no endpoint is registered.</li>
 * <li>{@code JsonSchemaValidatorSupplier#get()} builds a networknt validator, which loads
 * its draft 2020-12 meta-schema from a {@code classpath:} resource. Those resources sit at
 * the root of the networknt bundle ({@code draft/2020-12/schema}) and are exported by
 * nothing, so a version that reaches them only through the thread context classloader
 * cannot be constructed here. networknt 3.0.4 could not; 3.0.6 can, which is why this test
 * exists rather than the classloader workaround it replaced.</li>
 * </ul>
 * A bump of the MCP SDK or of networknt is what would break either one, so this belongs
 * next to the component that declares the references, not in a downstream deployment.
 */
@ExtendWith(BundleContextExtension.class)
public class MCPServerActivationTest {

	private static final String TOOL_PROVIDER_PID = "MCPToolProvider";
	private static final String HTTP_SERVER_PID = "HttpMCPServerComponent";
	private static final String TEST_TOOL_NAME = "activation_probe";

	/** Generous: the whiteboard, SCR and Configurator all have to settle first. */
	private static final long SERVICE_TIMEOUT_MS = 10_000L;

	private ServiceRegistration<MCPTool> toolRegistration;
	private Configuration toolProviderConfig;
	private Configuration httpServerConfig;

	@AfterEach
	void cleanUp() throws IOException {
		if (httpServerConfig != null) {
			httpServerConfig.delete();
		}
		if (toolProviderConfig != null) {
			toolProviderConfig.delete();
		}
		if (toolRegistration != null) {
			toolRegistration.unregister();
		}
	}

	/**
	 * The SDK ships both suppliers as {@code META-INF/services} entries and declares no
	 * {@code osgi.serviceloader} capability, so this fails unless the runtime contains
	 * something that registers them as OSGi services.
	 */
	@Test
	public void testJsonSupplierServicesAreRegistered(@InjectBundleContext BundleContext context) {
		assertNotNull(context.getServiceReference(McpJsonMapperSupplier.class),
				"No McpJsonMapperSupplier service. HttpMCPServerComponent and MCPToolProvider both "
						+ "declare a mandatory reference to it, so neither can activate.");
		assertNotNull(context.getServiceReference(JsonSchemaValidatorSupplier.class),
				"No JsonSchemaValidatorSupplier service. HttpMCPServerComponent declares a mandatory "
						+ "reference to it, so it can never activate.");
	}

	/**
	 * Constructing the validator is the step that trips over the classpath meta-schema
	 * lookup. Deliberately does not touch the TCCL: the point is that the supplier has to
	 * work as the component calls it.
	 */
	@Test
	public void testJsonSchemaValidatorCanBeConstructed(@InjectBundleContext BundleContext context) {
		ServiceReference<JsonSchemaValidatorSupplier> reference = context
				.getServiceReference(JsonSchemaValidatorSupplier.class);
		assertNotNull(reference, "No JsonSchemaValidatorSupplier service to construct a validator from");
		JsonSchemaValidatorSupplier supplier = context.getService(reference);
		try {
			JsonSchemaValidator validator = supplier.get();
			assertNotNull(validator, "The supplier returned no validator");
		} catch (RuntimeException e) {
			fail("Could not build the JSON schema validator under OSGi: " + e, e);
		} finally {
			context.ungetService(reference);
		}
	}

	/**
	 * End to end: one tool, one provider, one server configuration - an
	 * {@link MCPServer} service has to show up. This is what actually broke when the
	 * validator could not be built.
	 */
	@Test
	public void testMCPServerActivatesWithOneTool(@InjectBundleContext BundleContext context) throws Exception {
		ServiceReference<ConfigurationAdmin> cmReference = context.getServiceReference(ConfigurationAdmin.class);
		assertNotNull(cmReference, "No ConfigurationAdmin in the test runtime");
		ConfigurationAdmin cm = context.getService(cmReference);

		Dictionary<String, Object> toolProperties = new Hashtable<>();
		toolProperties.put("name", TEST_TOOL_NAME);
		toolRegistration = context.registerService(MCPTool.class, new ProbeTool(), toolProperties);

		toolProviderConfig = cm.getFactoryConfiguration(TOOL_PROVIDER_PID, "test", "?");
		Dictionary<String, Object> providerProperties = new Hashtable<>();
		providerProperties.put("name", "test-provider");
		providerProperties.put("description", "Tool provider for the activation test");
		providerProperties.put("tools.target", "(name=" + TEST_TOOL_NAME + ")");
		providerProperties.put("tools.cardinality.minimum", 1);
		toolProviderConfig.update(providerProperties);

		httpServerConfig = cm.getFactoryConfiguration(HTTP_SERVER_PID, "test", "?");
		Dictionary<String, Object> serverProperties = new Hashtable<>();
		serverProperties.put("server.name", "activation-test-server");
		serverProperties.put("server.full.url", "http://localhost:8085/test/mcp/message");
		serverProperties.put("osgi.http.whiteboard.servlet.pattern", "/test/mcp/message");
		serverProperties.put("osgi.http.whiteboard.target", "(osgi.http.endpoint=*)");
		serverProperties.put("has.tool.capability", true);
		serverProperties.put("toolProviders.target", "(name=test-provider)");
		serverProperties.put("toolProviders.cardinality.minimum", 1);
		httpServerConfig.update(serverProperties);

		ServiceReference<MCPServer> serverReference = waitForServiceReference(context, MCPServer.class);
		assertNotNull(serverReference,
				"HttpMCPServerComponent did not register an MCPServer service within " + SERVICE_TIMEOUT_MS
						+ " ms - check the SCR runtime for an unsatisfied reference");

		// HttpMCPServerComponent is a delayed component: SCR publishes the service
		// registration before it ever runs @Activate, so a non-null reference proves
		// nothing. Only getService() forces activation, and it hands back null when the
		// activate method threw.
		MCPServer server = context.getService(serverReference);
		try {
			assertNotNull(server, "The MCPServer service registration resolves to null - "
					+ "HttpMCPServerComponent.activate() threw. Check the framework log for the cause.");
		} finally {
			context.ungetService(serverReference);
			context.ungetService(cmReference);
		}
	}

	private <T> ServiceReference<T> waitForServiceReference(BundleContext context, Class<T> serviceType)
			throws InterruptedException {
		long deadline = System.currentTimeMillis() + SERVICE_TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline) {
			ServiceReference<T> reference = context.getServiceReference(serviceType);
			if (reference != null) {
				return reference;
			}
			Thread.sleep(100L);
		}
		return null;
	}

	/**
	 * Minimal {@link MCPTool} - the provider needs at least one, and none of the tests
	 * call it.
	 */
	private static final class ProbeTool implements MCPTool {

		@Override
		public String getName() {
			return TEST_TOOL_NAME;
		}

		@Override
		public String getDescription() {
			return "Probe tool that exists so the tool provider can be satisfied";
		}

		@Override
		public String getInputSchema() {
			return "{\"type\":\"object\",\"properties\":{}}";
		}

		@Override
		public String getOutputSchema() {
			return null;
		}

		@Override
		public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
			return Mono.error(new UnsupportedOperationException("The probe tool is never called"));
		}
	}
}
