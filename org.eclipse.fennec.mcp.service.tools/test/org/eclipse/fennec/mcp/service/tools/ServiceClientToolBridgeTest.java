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
package org.eclipse.fennec.mcp.service.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.service.api.ServiceClient;
import org.eclipse.fennec.service.api.ServiceInvocationException;
import org.eclipse.fennec.service.api.ServiceOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Tests tool registration (naming, allow-list, marker properties, lifecycle)
 * and the execution path of the {@link ServiceClientToolBridge} against
 * scripted {@link ServiceClient}s — the codec seams are faked, the real codec
 * conversion is covered by the cross-repo integration test (M4).
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
class ServiceClientToolBridgeTest {

	private record TestOperation(String name, EClass requestType, EClass responseType) implements ServiceOperation {
	}

	private final List<MCPTool> registeredTools = new ArrayList<>();
	private final List<Dictionary<String, ?>> registeredProps = new ArrayList<>();
	private final List<ServiceRegistration<MCPTool>> registrations = new ArrayList<>();

	private ServiceClientToolBridge bridge;
	private BundleContext context;
	private EClass requestClass;
	private EClass responseClass;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		EPackage ePackage = EcoreFactory.eINSTANCE.createEPackage();
		ePackage.setName("weather");
		ePackage.setNsPrefix("w");
		ePackage.setNsURI("http://example.org/weather");
		requestClass = eClass(ePackage, "WeatherRequest");
		responseClass = eClass(ePackage, "WeatherResponse");

		context = mock(BundleContext.class);
		when(context.registerService(eq(MCPTool.class), any(MCPTool.class), any(Dictionary.class)))
				.thenAnswer(invocation -> {
					MCPTool tool = invocation.getArgument(1);
					Dictionary<String, ?> props = invocation.getArgument(2);
					registeredTools.add(tool);
					registeredProps.add(props);
					ServiceRegistration<MCPTool> registration = mock(ServiceRegistration.class);
					ServiceReference<MCPTool> reference = mock(ServiceReference.class);
					when(registration.getReference()).thenReturn(reference);
					when(reference.getProperty("tool.name")).thenReturn(props.get("tool.name"));
					registrations.add(registration);
					return registration;
				});

		bridge = new ServiceClientToolBridge();
		bridge.schemaGenerator = c -> c == null ? CodecPayloads.EMPTY_OBJECT_SCHEMA : "schema-of-" + c.getName();
		bridge.payloadCodec = mock(ServiceOperationTool.PayloadCodec.class);
	}

	private static EClass eClass(EPackage owner, String name) {
		EClass eClass = EcoreFactory.eINSTANCE.createEClass();
		eClass.setName(name);
		owner.getEClassifiers().add(eClass);
		return eClass;
	}

	private ServiceClient client(String name, ServiceOperation... operations) {
		ServiceClient client = mock(ServiceClient.class);
		doReturn(List.of(operations)).when(client).operations();
		return client;
	}

	private void activate(String prefix, String... allow) {
		bridge.activate(context, config(prefix, allow), Map.of("service.pid", "ServiceClientToolBridge~petstore"));
	}

	@Test
	void bindRegistersOneToolPerAllowedOperationWithMarkerProperties() {
		ServiceClient client = client("weather",
				new TestOperation("getWeather", requestClass, responseClass),
				new TestOperation("deleteStation", requestClass, null));
		activate("", "getWeather");
		bridge.addClient(client, Map.of(ServiceClient.PROP_NAME, "weather"));

		assertThat(registeredTools).hasSize(1);
		MCPTool tool = registeredTools.get(0);
		assertThat(tool.getName()).isEqualTo("weather_getweather");
		assertThat(tool.getInputSchema()).isEqualTo("schema-of-WeatherRequest");
		assertThat(tool.getOutputSchema()).isEqualTo("schema-of-WeatherResponse");
		assertThat(registeredProps.get(0).get("tool.name")).isEqualTo("weather_getweather");
		assertThat(registeredProps.get(0).get("tool.namespace")).isEqualTo("service-bridge");
	}

	@Test
	void emptyAllowListExposesNothing() {
		activate("");
		bridge.addClient(client("weather", new TestOperation("getWeather", requestClass, responseClass)), Map.of());
		assertThat(registeredTools).isEmpty();
	}

	@Test
	void prefixGlobAndConfigPrefixApply() {
		ServiceClient client = client("ignored",
				new TestOperation("getWeather", requestClass, responseClass),
				new TestOperation("getStation", null, responseClass),
				new TestOperation("putStation", requestClass, null));
		activate("wx", "get*");
		bridge.addClient(client, Map.of(ServiceClient.PROP_NAME, "weather"));

		assertThat(registeredTools).extracting(MCPTool::getName)
				.containsExactly("wx_getweather", "wx_getstation");
		// an operation without request type gets the empty-object schema
		assertThat(registeredTools.get(1).getInputSchema()).isEqualTo(CodecPayloads.EMPTY_OBJECT_SCHEMA);
	}

	@Test
	void duplicateToolNamesAreSkipped() {
		activate("", "op");
		bridge.addClient(client("same", new TestOperation("op", requestClass, responseClass)), Map.of(ServiceClient.PROP_NAME, "same"));
		bridge.addClient(client("same", new TestOperation("op", requestClass, responseClass)), Map.of(ServiceClient.PROP_NAME, "same"));
		assertThat(registeredTools).hasSize(1);
	}

	@Test
	void unbindUnregistersAndFreesTheName() {
		ServiceClient client = client("weather", new TestOperation("getWeather", requestClass, responseClass));
		activate("", "getWeather");
		bridge.addClient(client, Map.of(ServiceClient.PROP_NAME, "weather"));
		bridge.removeClient(client);

		verify(registrations.get(0)).unregister();
		// name is free again for a re-bound client
		bridge.addClient(client, Map.of(ServiceClient.PROP_NAME, "weather"));
		assertThat(registeredTools).hasSize(2);
	}

	@Test
	void configInstanceNameIsTheFallbackPrefix() {
		activate("", "op");
		bridge.addClient(client("x", new TestOperation("op", requestClass, responseClass)), Map.of());
		assertThat(registeredTools.get(0).getName()).isEqualTo("petstore_op");
	}

	@Test
	void executeInvokesTheClientAndReturnsTheJsonResponse() {
		ServiceOperation operation = new TestOperation("getWeather", requestClass, responseClass);
		ServiceClient client = client("weather", operation);
		activate("", "getWeather");
		bridge.addClient(client, Map.of(ServiceClient.PROP_NAME, "weather"));

		EObject request = EcoreFactory.eINSTANCE.createEObject();
		EObject response = EcoreFactory.eINSTANCE.createEObject();
		when(bridge.payloadCodec.toRequest(eq(operation), any())).thenReturn(request);
		when(client.invoke(operation, request)).thenReturn(response);
		when(bridge.payloadCodec.toJson(response)).thenReturn("{\"temp\":21}");

		McpSchema.CallToolResult result = registeredTools.get(0)
				.execute(null, Map.of("city", "Jena")).block();

		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("{\"temp\":21}");
	}

	@Test
	void invocationFailureBecomesAnErrorResultWithoutStackTrace() {
		ServiceOperation operation = new TestOperation("getWeather", requestClass, responseClass);
		ServiceClient client = client("weather", operation);
		activate("", "getWeather");
		bridge.addClient(client, Map.of(ServiceClient.PROP_NAME, "weather"));

		when(bridge.payloadCodec.toRequest(eq(operation), any())).thenReturn(EcoreFactory.eINSTANCE.createEObject());
		when(client.invoke(eq(operation), any())).thenThrow(new ServiceInvocationException("HTTP 502 from upstream"));

		McpSchema.CallToolResult result = registeredTools.get(0)
				.execute(null, Map.of()).block();

		assertThat(result.isError()).isTrue();
		assertThat(((McpSchema.TextContent) result.content().get(0)).text())
				.isEqualTo("Service invocation failed: HTTP 502 from upstream");
	}

	@Test
	void ecoreDataTypesResolveInDynamicClasses() {
		// guard: the request class may reference Ecore built-ins without breaking schema generation
		var attribute = EcoreFactory.eINSTANCE.createEAttribute();
		attribute.setName("city");
		attribute.setEType(EcorePackage.Literals.ESTRING);
		requestClass.getEStructuralFeatures().add(attribute);
		activate("", "getWeather");
		bridge.addClient(client("weather", new TestOperation("getWeather", requestClass, responseClass)),
				Map.of(ServiceClient.PROP_NAME, "weather"));
		assertThat(registeredTools.get(0).getInputSchema()).contains("WeatherRequest");
	}

	private static ServiceToolsConfig config(String prefix, String... allow) {
		return new ServiceToolsConfig() {
			@Override
			public Class<? extends Annotation> annotationType() {
				return ServiceToolsConfig.class;
			}

			@Override
			public String[] operations_allow() {
				return allow;
			}

			@Override
			public String tools_prefix() {
				return prefix;
			}
		};
	}
}
