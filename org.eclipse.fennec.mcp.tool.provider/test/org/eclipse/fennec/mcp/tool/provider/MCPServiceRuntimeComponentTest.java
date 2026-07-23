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
package org.eclipse.fennec.mcp.tool.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Dictionary;
import java.util.List;
import java.util.Map;

import org.eclipse.fennec.mcp.api.MCPServer;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.api.MCPToolProvider;
import org.eclipse.fennec.mcp.api.runtime.MCPRuntimeDTO;
import org.eclipse.fennec.mcp.api.runtime.MCPServiceRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests DTO assembly and the {@code service.changecount} contract of the
 * {@link MCPServiceRuntimeComponent}.
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
class MCPServiceRuntimeComponentTest {

	private static final String SCHEMA = """
			{ "type": "object", "properties": {} }
			""";

	private MCPServiceRuntimeComponent runtime;
	private BundleContext context;
	private ServiceRegistration<MCPServiceRuntime> registration;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		runtime = new MCPServiceRuntimeComponent();
		context = mock(BundleContext.class);
		registration = mock(ServiceRegistration.class);
		when(context.registerService(eq(MCPServiceRuntime.class), same(runtime), any())).thenReturn(registration);
		runtime.activate(context);
	}

	private static McpServerFeatures.AsyncToolSpecification spec(String name) {
		Tool tool = Tool.builder(name, new JacksonMcpJsonMapper(new JsonMapper()), SCHEMA).description(name).build();
		return McpServerFeatures.AsyncToolSpecification.builder()
				.tool(tool)
				.callHandler((exchange, request) -> Mono.empty())
				.build();
	}

	@Test
	void runtimeDtoReflectsServersProvidersAndTools() {
		MCPServer server = mock(MCPServer.class);
		when(server.getServerName()).thenReturn("osgi-emf-mcp-server");
		when(server.getServerFullUrl()).thenReturn("http://127.0.0.1:8090/mcp/emf");
		when(server.getTools()).thenReturn(List.of(spec("a"), spec("b")));
		when(server.getPrompts()).thenReturn(List.of());
		when(server.getResources()).thenReturn(List.of());
		runtime.addServer(server);

		MCPToolProvider provider = mock(MCPToolProvider.class);
		when(provider.getDescription()).thenReturn("EMF tools");
		when(provider.getMCPTools()).thenReturn(List.of(spec("export_dataset"), spec("create_dataset")));
		runtime.addProvider(provider, Map.of("name", "emf_model_tool_provider"));

		MCPTool tool = mock(MCPTool.class);
		when(tool.getName()).thenReturn("export_dataset");
		when(tool.getDescription()).thenReturn("Serialize a dataset");
		runtime.addTool(tool);

		MCPRuntimeDTO dto = runtime.getRuntimeDTO();

		assertThat(dto.servers).hasSize(1);
		assertThat(dto.servers[0].name).isEqualTo("osgi-emf-mcp-server");
		assertThat(dto.servers[0].url).isEqualTo("http://127.0.0.1:8090/mcp/emf");
		assertThat(dto.servers[0].toolCount).isEqualTo(2);
		assertThat(dto.servers[0].promptCount).isZero();

		assertThat(dto.toolProviders).hasSize(1);
		assertThat(dto.toolProviders[0].name).isEqualTo("emf_model_tool_provider");
		assertThat(dto.toolProviders[0].description).isEqualTo("EMF tools");
		assertThat(dto.toolProviders[0].tools).containsExactly("create_dataset", "export_dataset");

		assertThat(dto.tools).hasSize(1);
		assertThat(dto.tools[0].name).isEqualTo("export_dataset");
	}

	@Test
	void unboundServicesDisappearFromTheDto() {
		MCPTool tool = mock(MCPTool.class);
		when(tool.getName()).thenReturn("t");
		runtime.addTool(tool);
		assertThat(runtime.getRuntimeDTO().tools).hasSize(1);
		runtime.removeTool(tool);
		assertThat(runtime.getRuntimeDTO().tools).isEmpty();
	}

	@Test
	@SuppressWarnings("unchecked")
	void bindAndUnbindBumpTheServiceChangecount() {
		MCPTool tool = mock(MCPTool.class);
		runtime.addTool(tool);
		runtime.removeTool(tool);

		ArgumentCaptor<Dictionary<String, ?>> properties = ArgumentCaptor.forClass(Dictionary.class);
		verify(registration, atLeastOnce()).setProperties(properties.capture());
		List<Long> counts = properties.getAllValues().stream()
				.map(p -> (Long) p.get(Constants.SERVICE_CHANGECOUNT))
				.toList();
		assertThat(counts).hasSizeGreaterThanOrEqualTo(2).isSorted().doesNotHaveDuplicates();
	}

	@Test
	void deactivateUnregistersTheService() {
		runtime.deactivate();
		verify(registration).unregister();
	}
}
