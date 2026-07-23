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
package org.eclipse.fennec.mcp.service.tools.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;

import org.eclipse.fennec.mcp.api.MCPTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.common.annotation.InjectService;
import org.osgi.test.junit5.context.BundleContextExtension;
import org.osgi.test.junit5.service.ServiceExtension;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Cross-repo proof for the ServiceClient→MCPTool bridge: an embedded
 * {@link HttpServer} serves an OpenAPI document and its {@code ping}
 * endpoint; a ConfigAdmin factory config turns it into a
 * {@code ServiceClient} (emf.util's {@code OpenApiServiceClient}), a second
 * factory config bridges it — and the imported operation surfaces as a
 * callable {@code MCPTool} service that round-trips JSON over HTTP through
 * the codec, all inside a running Felix.
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
@ExtendWith(BundleContextExtension.class)
@ExtendWith(ServiceExtension.class)
@DisplayName("ServiceClient to MCP tool bridge")
public class ServiceClientBridgeIntegrationTest {

	private static final String OPENAPI_DOCUMENT = """
			{
			  "openapi": "3.0.0",
			  "info": { "title": "Ping", "version": "1.0.0" },
			  "paths": {
			    "/ping": {
			      "get": {
			        "operationId": "ping",
			        "responses": {
			          "200": {
			            "description": "a pong",
			            "content": {
			              "application/json": {
			                "schema": { "$ref": "#/components/schemas/Pong" }
			              }
			            }
			          }
			        }
			      }
			    },
			    "/secret": {
			      "get": {
			        "operationId": "secret",
			        "responses": { "200": { "description": "never exposed" } }
			      }
			    }
			  },
			  "components": {
			    "schemas": {
			      "Pong": {
			        "type": "object",
			        "properties": { "message": { "type": "string" } }
			      }
			    }
			  }
			}
			""";

	@Test
	@DisplayName("an imported OpenAPI operation surfaces as a callable MCP tool")
	void importedOperationBecomesACallableTool(@InjectBundleContext BundleContext context,
			@InjectService(timeout = 5000) ConfigurationAdmin configurationAdmin) throws Exception {

		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/openapi.json", exchange -> respond(exchange, OPENAPI_DOCUMENT));
		server.createContext("/ping", exchange -> respond(exchange, "{\"message\":\"pong\"}"));
		server.start();
		int port = server.getAddress().getPort();

		Configuration clientConfig = configurationAdmin.createFactoryConfiguration("OpenApiServiceClient", "?");
		Configuration bridgeConfig = configurationAdmin.createFactoryConfiguration("ServiceClientToolBridge", "?");
		try {
			Dictionary<String, Object> clientProperties = new Hashtable<>();
			clientProperties.put("name", "ping-client");
			clientProperties.put("documentUrl", "http://localhost:" + port + "/openapi.json");
			clientProperties.put("baseUri", "http://localhost:" + port);
			clientProperties.put("format", "json");
			clientConfig.update(clientProperties);

			Dictionary<String, Object> bridgeProperties = new Hashtable<>();
			bridgeProperties.put("clients.target", "(name=ping-client)");
			bridgeProperties.put("operations.allow", new String[] { "ping" });
			bridgeConfig.update(bridgeProperties);

			ServiceReference<MCPTool> reference = awaitTool(context, "ping-client_ping", 10_000);
			assertNotNull(reference, "the bridged operation should be published as an MCPTool service");
			assertEquals("service-bridge", reference.getProperty("tool.namespace"),
					"bridged tools must carry the provider-selection marker");

			MCPTool tool = context.getService(reference);
			assertNotNull(tool);
			assertNotNull(tool.getInputSchema(), "the tool must carry a generated input schema");
			assertTrue(tool.getOutputSchema().contains("message"),
					"the output schema should describe the Pong type, but was: " + tool.getOutputSchema());

			McpSchema.CallToolResult result = tool.execute(null, Map.of()).block();
			assertNotNull(result);
			assertTrue(result.isError() == null || !result.isError(),
					"the invocation should succeed, but was: " + result.content());
			String text = ((McpSchema.TextContent) result.content().get(0)).text();
			assertTrue(text.contains("pong"), "the response JSON should carry the pong message, but was: " + text);

			// deny-all: the non-allow-listed operation must not surface
			assertNull(findTool(context, "ping-client_secret"), "operations outside the allow-list must not be bridged");

			// lifecycle: deleting the bridge config retracts the tool
			bridgeConfig.delete();
			long deadline = System.currentTimeMillis() + 10_000;
			while (findTool(context, "ping-client_ping") != null && System.currentTimeMillis() < deadline) {
				Thread.sleep(100);
			}
			assertNull(findTool(context, "ping-client_ping"), "deleting the bridge config must unregister the tool");
		} finally {
			clientConfig.delete();
			server.stop(0);
		}
	}

	private static void respond(HttpExchange exchange, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static ServiceReference<MCPTool> awaitTool(BundleContext context, String toolName, long timeoutMillis)
			throws InterruptedException, InvalidSyntaxException {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < deadline) {
			ServiceReference<MCPTool> reference = findTool(context, toolName);
			if (reference != null) {
				return reference;
			}
			Thread.sleep(100);
		}
		return null;
	}

	private static ServiceReference<MCPTool> findTool(BundleContext context, String toolName)
			throws InvalidSyntaxException {
		return context.getServiceReferences(MCPTool.class, "(tool.name=" + toolName + ")")
				.stream().findFirst().orElse(null);
	}
}
