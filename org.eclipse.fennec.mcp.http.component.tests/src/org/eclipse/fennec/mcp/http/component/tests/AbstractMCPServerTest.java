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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.eclipse.fennec.mcp.api.MCPTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.servlet.runtime.HttpServiceRuntime;
import org.osgi.service.servlet.runtime.HttpServiceRuntimeConstants;
import org.osgi.service.servlet.runtime.dto.RuntimeDTO;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;

/**
 * Shared plumbing for the {@code HttpMCPServerComponent} integration tests: builds the
 * configurations a server needs, hands out MCP clients that speak to the running endpoint,
 * and undoes all of it afterwards.
 * <p>
 * Every test drives the component the way a deployment does - through factory
 * configurations for {@code MCPToolProvider} and {@code HttpMCPServerComponent} - rather
 * than instantiating it, so what is asserted is the DS and whiteboard wiring and not just
 * the Java code.
 */
public abstract class AbstractMCPServerTest {

	/** Generous: the whiteboard, SCR and Configurator all have to settle first. */
	protected static final long TIMEOUT_MS = 10_000L;

	private static final long POLL_INTERVAL_MS = 50L;

	/** Service property the test tools carry so a provider filter can select them. */
	protected static final String TOOL_GROUP = "test.tool.group";

	private final List<Configuration> configurations = new ArrayList<>();
	private final List<ServiceRegistration<?>> registrations = new ArrayList<>();
	private final List<McpSyncClient> clients = new ArrayList<>();

	/**
	 * Tears the runtime back down in the reverse order it was built up. Configurations go
	 * first so the components deactivate while their dependencies are still there, which
	 * is also what exercises {@code unregisterMCPServer()}.
	 */
	@AfterEach
	void tearDownRuntime() throws IOException {
		clients.forEach(client -> {
			try {
				client.closeGracefully();
			} catch (RuntimeException e) {
				// a test may have closed it already, or asserted a broken session
			}
		});
		clients.clear();
		for (int i = configurations.size() - 1; i >= 0; i--) {
			try {
				configurations.get(i).delete();
			} catch (IllegalStateException e) {
				// the test deleted this one itself, which is what it was testing
			}
		}
		configurations.clear();
		for (int i = registrations.size() - 1; i >= 0; i--) {
			registrations.get(i).unregister();
		}
		registrations.clear();
	}

	/**
	 * Creates an {@code MCPToolProvider} factory configuration selecting the tools of one
	 * {@link #TOOL_GROUP}.
	 *
	 * @param minimum the {@code tools.cardinality.minimum}; the provider stays unsatisfied
	 *            until that many matching {@link MCPTool} services exist
	 */
	protected Configuration createToolProvider(ConfigurationAdmin cm, String instance, String providerName,
			String toolGroup, int minimum) throws IOException {
		Dictionary<String, Object> properties = new Hashtable<>();
		properties.put("name", providerName);
		properties.put("description", "Tool provider " + providerName + " of the HTTP component tests");
		properties.put("tools.target", "(" + TOOL_GROUP + "=" + toolGroup + ")");
		properties.put("tools.cardinality.minimum", minimum);
		return createConfiguration(cm, "MCPToolProvider", instance, properties);
	}

	/**
	 * The properties of a server that publishes {@code providerName}'s tools at
	 * {@code servletPattern}. Mutable: a test adds or overrides what it is about (a token,
	 * a capability toggle, a verifier target) before handing it to
	 * {@link #createServer(ConfigurationAdmin, String, Dictionary)}.
	 */
	protected Dictionary<String, Object> serverProperties(String serverName, String servletPattern,
			String providerName) {
		Dictionary<String, Object> properties = new Hashtable<>();
		properties.put("server.name", serverName);
		properties.put("server.full.url", "http://localhost" + servletPattern);
		properties.put("osgi.http.whiteboard.servlet.pattern", servletPattern);
		properties.put("osgi.http.whiteboard.target", "(osgi.http.endpoint=*)");
		properties.put("has.tool.capability", true);
		properties.put("toolProviders.target", "(name=" + providerName + ")");
		properties.put("toolProviders.cardinality.minimum", 1);
		return properties;
	}

	/** Creates the {@code HttpMCPServerComponent} factory configuration. */
	protected Configuration createServer(ConfigurationAdmin cm, String instance,
			Dictionary<String, Object> properties) throws IOException {
		return createConfiguration(cm, "HttpMCPServerComponent", instance, properties);
	}

	/** Creates a factory configuration and schedules its deletion. */
	protected Configuration createConfiguration(ConfigurationAdmin cm, String factoryPid, String instance,
			Dictionary<String, Object> properties) throws IOException {
		Configuration configuration = cm.getFactoryConfiguration(factoryPid, instance, "?");
		configurations.add(configuration);
		configuration.update(properties);
		return configuration;
	}

	/** Registers a test tool under {@code toolGroup} and schedules its unregistration. */
	protected ServiceRegistration<MCPTool> registerTool(BundleContext context, MCPTool tool, String toolGroup) {
		Dictionary<String, Object> properties = new Hashtable<>();
		properties.put(TOOL_GROUP, toolGroup);
		properties.put("tool.name", tool.getName());
		ServiceRegistration<MCPTool> registration = context.registerService(MCPTool.class, tool, properties);
		registrations.add(registration);
		return registration;
	}

	/** Registers any service and schedules its unregistration. */
	protected <S> ServiceRegistration<S> registerService(BundleContext context, Class<S> type, S service,
			Dictionary<String, Object> properties) {
		ServiceRegistration<S> registration = context.registerService(type, service, properties);
		registrations.add(registration);
		return registration;
	}

	/** Forgets a registration this test already unregistered itself. */
	protected void forget(ServiceRegistration<?> registration) {
		registrations.remove(registration);
	}

	/**
	 * Waits for a service matching {@code filter} and returns it, forcing activation of
	 * the component behind it.
	 * <p>
	 * Both steps matter for a delayed component: SCR publishes the service registration
	 * before it ever runs {@code @Activate}, so a non-null reference proves nothing - only
	 * {@code getService()} forces activation, and it hands back {@code null} when the
	 * activate method threw.
	 */
	protected static <S> S awaitService(BundleContext context, Class<S> type, String filter) {
		ServiceReference<S> reference = awaitServiceReference(context, type, filter);
		S service = context.getService(reference);
		assertNotNull(service, () -> "The " + type.getSimpleName() + " registration matching " + filter
				+ " resolves to null - its activate method threw. Check the framework log for the cause.");
		return service;
	}

	/** Waits for a service reference matching {@code filter}, failing if none shows up. */
	protected static <S> ServiceReference<S> awaitServiceReference(BundleContext context, Class<S> type,
			String filter) {
		Supplier<ServiceReference<S>> lookup = () -> serviceReferences(context, type, filter).stream().findFirst()
				.orElse(null);
		ServiceReference<S> reference = awaitValue(lookup);
		assertNotNull(reference, () -> "No " + type.getSimpleName() + " service matching " + filter + " within "
				+ TIMEOUT_MS + " ms - check the SCR runtime for an unsatisfied reference");
		return reference;
	}

	/** All references of {@code type} matching {@code filter}, never {@code null}. */
	protected static <S> Collection<ServiceReference<S>> serviceReferences(BundleContext context, Class<S> type,
			String filter) {
		try {
			Collection<ServiceReference<S>> references = context.getServiceReferences(type, filter);
			return references == null ? Collections.emptyList() : references;
		} catch (InvalidSyntaxException e) {
			throw new IllegalArgumentException("Broken test filter: " + filter, e);
		}
	}

	/** Polls {@code condition} until it holds, failing with {@code message} if it never does. */
	protected static void awaitCondition(String message, BooleanSupplier condition) {
		Boolean satisfied = awaitValue(() -> condition.getAsBoolean() ? Boolean.TRUE : null);
		if (satisfied == null) {
			fail(message + " (not within " + TIMEOUT_MS + " ms)");
		}
	}

	/** Polls until {@code supplier} yields a non-null value, or the timeout expires. */
	protected static <T> T awaitValue(Supplier<T> supplier) {
		long deadline = System.currentTimeMillis() + TIMEOUT_MS;
		for (;;) {
			T value = supplier.get();
			if (value != null) {
				return value;
			}
			if (System.currentTimeMillis() >= deadline) {
				return null;
			}
			try {
				Thread.sleep(POLL_INTERVAL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
		}
	}

	/**
	 * The loopback base URL of the running HTTP Whiteboard. The test runtime binds an
	 * ephemeral port, and the {@code osgi.http.endpoint} property the servlet
	 * configurations target is the only place that port is published.
	 * <p>
	 * Only the port is taken from it: Felix reports the endpoint under the machine's own
	 * host name when Jetty is bound to all interfaces, and connecting to that name would
	 * arrive from a routable address - which the authentication filter refuses by default,
	 * since without a configured token it trusts loopback callers only.
	 */
	protected static String httpBaseUrl(BundleContext context) {
		ServiceReference<HttpServiceRuntime> reference = httpServiceRuntimeReference(context);
		Object endpoints = reference.getProperty(HttpServiceRuntimeConstants.HTTP_SERVICE_ENDPOINT);
		String endpoint = firstEndpoint(endpoints);
		assertNotNull(endpoint, () -> "The HttpServiceRuntime publishes no "
				+ HttpServiceRuntimeConstants.HTTP_SERVICE_ENDPOINT + " (got: " + endpoints + ")");
		int port = URI.create(endpoint).getPort();
		if (port <= 0) {
			fail("The HTTP Whiteboard endpoint " + endpoint + " names no port");
		}
		return "http://127.0.0.1:" + port;
	}

	/** The objectClasses of every service whose name mentions HTTP - a lookup failure's context. */
	private static List<String> httpServices(BundleContext context) {
		try {
			ServiceReference<?>[] references = context.getAllServiceReferences(null, null);
			if (references == null) {
				return List.of();
			}
			return Arrays.stream(references)
					.flatMap(reference -> Arrays.stream((String[]) reference.getProperty(Constants.OBJECTCLASS)))
					.filter(objectClass -> objectClass.toLowerCase().contains("http"))
					.distinct()
					.toList();
		} catch (InvalidSyntaxException e) {
			return List.of();
		}
	}

	private static String firstEndpoint(Object endpoints) {
		if (endpoints instanceof String single) {
			return single;
		}
		if (endpoints instanceof String[] array) {
			return array.length == 0 ? null : array[0];
		}
		if (endpoints instanceof Collection<?> collection) {
			return collection.stream().findFirst().map(Object::toString).orElse(null);
		}
		return null;
	}

	/** The whiteboard's current view of what is registered and what failed. */
	protected static RuntimeDTO runtimeDTO(BundleContext context) {
		ServiceReference<HttpServiceRuntime> reference = httpServiceRuntimeReference(context);
		HttpServiceRuntime runtime = context.getService(reference);
		assertNotNull(runtime, "The HttpServiceRuntime registration resolves to null");
		try {
			return runtime.getRuntimeDTO();
		} finally {
			context.ungetService(reference);
		}
	}

	/**
	 * The running whiteboard, or an aborted test if the HTTP implementation itself never
	 * started.
	 * <p>
	 * Aries SpiFly weaves every bundle that declares itself a {@code ServiceLoader}
	 * consumer - Felix's Jetty bundle is one - and its weaving hook fails on class files
	 * the ASM behind it cannot read, which kills Jetty in its own activator. That is why
	 * {@code test.bndrun} blacklists SpiFly's framework extension, whose ASM is embedded
	 * and stuck at class file V22, in favour of the {@code dynamic.bundle} variant that
	 * imports {@code org.objectweb.asm}: the deployed ASM then decides, and a current one
	 * reads current class files. SpiFly cannot simply be dropped instead - it is what turns
	 * the MCP SDK's {@code META-INF/services} suppliers into OSGi services, without which
	 * {@code HttpMCPServerComponent} never activates.
	 * <p>
	 * So this should not trigger. It stays as a guard: if that composition regresses on a
	 * future JDK, nothing about our own wiring is observable anyway and none of it is what
	 * broke, so the endpoint tests skip with the reason rather than reporting failures of
	 * this workspace, and everything that needs no live endpoint keeps running.
	 */
	private static ServiceReference<HttpServiceRuntime> httpServiceRuntimeReference(BundleContext context) {
		ServiceReference<HttpServiceRuntime> reference = awaitValue(() -> {
			ServiceReference<HttpServiceRuntime> found = serviceReferences(context, HttpServiceRuntime.class, null)
					.stream().findFirst().orElse(null);
			if (found != null) {
				return found;
			}
			// Nothing to wait for once the implementation has given up: the launcher starts
			// every bundle before the first test, so one still sitting in RESOLVED failed
			// its activator and no amount of polling will produce a whiteboard.
			List<String> failed = failedHttpBundles(context);
			if (!failed.isEmpty()) {
				Assumptions.abort("The HTTP implementation did not start on this JVM (Java "
						+ Runtime.version().feature() + ", class file major version "
						+ (Runtime.version().feature() + 44) + "): " + failed
						+ ". See the framework log - Aries SpiFly's weaving hook fails on class files its ASM "
						+ "does not know, which kills the Felix Jetty bundle. Run on the JDK the runtime "
						+ "supports to enforce the endpoint tests.");
			}
			return null;
		});
		if (reference != null) {
			return reference;
		}
		return fail("No HttpServiceRuntime service, although no HTTP bundle failed to start. "
				+ "Registered HTTP-related services: " + httpServices(context));
	}

	/** The HTTP implementation bundles that are installed but gave up before ACTIVE. */
	private static List<String> failedHttpBundles(BundleContext context) {
		return Arrays.stream(context.getBundles())
				.filter(bundle -> bundle.getSymbolicName() != null && bundle.getSymbolicName().contains("http.jetty"))
				.filter(bundle -> bundle.getState() == Bundle.RESOLVED || bundle.getState() == Bundle.INSTALLED)
				.map(bundle -> bundle.getSymbolicName() + " (state " + bundle.getState() + ")")
				.toList();
	}

	/**
	 * A real MCP client speaking Streamable HTTP to {@code servletPattern}, closed again
	 * after the test. Uses the framework's own {@code McpJsonMapper} rather than the SDK's
	 * {@code ServiceLoader} default.
	 *
	 * @param bearerToken sent as {@code Authorization: Bearer} on every request, or
	 *            {@code null} for an unauthenticated client
	 */
	protected McpSyncClient mcpClient(BundleContext context, String servletPattern, String bearerToken) {
		HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
		if (bearerToken != null) {
			requestBuilder.header("Authorization", "Bearer " + bearerToken);
		}
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
				.builder(httpBaseUrl(context))
				.endpoint(servletPattern)
				.jsonMapper(jsonMapper(context))
				.requestBuilder(requestBuilder)
				.build();
		McpSyncClient client = McpClient.sync(transport)
				.requestTimeout(Duration.ofSeconds(20))
				.initializationTimeout(Duration.ofSeconds(20))
				.build();
		clients.add(client);
		return client;
	}

	/** The {@code McpJsonMapper} the components themselves use. */
	protected static McpJsonMapper jsonMapper(BundleContext context) {
		return awaitService(context, McpJsonMapperSupplier.class, null).get();
	}

	/**
	 * POSTs an {@code initialize} request without going through the SDK client, so the
	 * plain HTTP status code of a rejected request is observable.
	 *
	 * @param bearerToken sent as {@code Authorization: Bearer}, or {@code null} to send no
	 *            authorization header at all
	 */
	protected static HttpResponse<String> postInitialize(BundleContext context, String servletPattern,
			String bearerToken) throws IOException, InterruptedException {
		String body = """
				{"jsonrpc":"2.0","id":1,"method":"initialize","params":{\
				"protocolVersion":"2025-06-18","capabilities":{},\
				"clientInfo":{"name":"http-component-tests","version":"1.0.0"}}}""";
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(httpBaseUrl(context) + servletPattern))
				.timeout(Duration.ofSeconds(20))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString(body));
		if (bearerToken != null) {
			request.header("Authorization", "Bearer " + bearerToken);
		}
		try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
			return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
		}
	}
}
