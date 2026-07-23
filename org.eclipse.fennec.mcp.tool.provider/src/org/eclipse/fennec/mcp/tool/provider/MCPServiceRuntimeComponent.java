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

import java.util.Comparator;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.fennec.mcp.api.MCPServer;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.api.MCPToolProvider;
import org.eclipse.fennec.mcp.api.runtime.MCPRuntimeDTO;
import org.eclipse.fennec.mcp.api.runtime.MCPServerDTO;
import org.eclipse.fennec.mcp.api.runtime.MCPServiceRuntime;
import org.eclipse.fennec.mcp.api.runtime.MCPToolDTO;
import org.eclipse.fennec.mcp.api.runtime.MCPToolProviderDTO;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

/**
 * OSGi service-runtime introspection of the MCP whiteboard (the pattern of
 * {@code HttpServiceRuntime}): tracks all {@link MCPServer},
 * {@link MCPToolProvider} and {@link MCPTool} services and registers
 * {@link MCPServiceRuntime} manually so it can bump the
 * {@link Constants#SERVICE_CHANGECOUNT service.changecount} property via
 * {@link ServiceRegistration#setProperties(java.util.Dictionary)} on every
 * bind/unbind. Consumers listen for the service-modified event and re-fetch
 * the DTO instead of polling.
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
@Component(name = "MCPServiceRuntime", immediate = true, service = {})
public class MCPServiceRuntimeComponent implements MCPServiceRuntime {

	private final Map<MCPServer, Boolean> servers = new ConcurrentHashMap<>();
	private final Map<MCPToolProvider, String> providers = new ConcurrentHashMap<>();
	private final Map<MCPTool, Boolean> tools = new ConcurrentHashMap<>();
	private final AtomicLong changeCount = new AtomicLong();
	private volatile ServiceRegistration<MCPServiceRuntime> registration;

	@Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
	void addServer(MCPServer server) {
		servers.put(server, Boolean.TRUE);
		bump();
	}

	void removeServer(MCPServer server) {
		servers.remove(server);
		bump();
	}

	@Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
	void addProvider(MCPToolProvider provider, Map<String, Object> properties) {
		providers.put(provider, properties.get("name") instanceof String name ? name : "");
		bump();
	}

	void removeProvider(MCPToolProvider provider) {
		providers.remove(provider);
		bump();
	}

	@Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
	void addTool(MCPTool tool) {
		tools.put(tool, Boolean.TRUE);
		bump();
	}

	void removeTool(MCPTool tool) {
		tools.remove(tool);
		bump();
	}

	@Activate
	void activate(BundleContext context) {
		registration = context.registerService(MCPServiceRuntime.class, this, properties());
	}

	@Deactivate
	void deactivate() {
		ServiceRegistration<MCPServiceRuntime> local = registration;
		registration = null;
		if (local != null) {
			local.unregister();
		}
	}

	@Override
	public MCPRuntimeDTO getRuntimeDTO() {
		MCPRuntimeDTO dto = new MCPRuntimeDTO();
		dto.servers = servers.keySet().stream()
				.map(MCPServiceRuntimeComponent::toDto)
				.sorted(Comparator.comparing(s -> s.name, Comparator.nullsFirst(Comparator.naturalOrder())))
				.toArray(MCPServerDTO[]::new);
		dto.toolProviders = providers.entrySet().stream()
				.map(entry -> toDto(entry.getKey(), entry.getValue()))
				.sorted(Comparator.comparing(p -> p.name, Comparator.nullsFirst(Comparator.naturalOrder())))
				.toArray(MCPToolProviderDTO[]::new);
		dto.tools = tools.keySet().stream()
				.map(MCPServiceRuntimeComponent::toDto)
				.sorted(Comparator.comparing(t -> t.name, Comparator.nullsFirst(Comparator.naturalOrder())))
				.toArray(MCPToolDTO[]::new);
		return dto;
	}

	private static MCPServerDTO toDto(MCPServer server) {
		MCPServerDTO dto = new MCPServerDTO();
		dto.name = server.getServerName();
		dto.url = server.getServerFullUrl();
		dto.toolCount = server.getTools().size();
		dto.promptCount = server.getPrompts().size();
		dto.resourceCount = server.getResources().size();
		return dto;
	}

	private static MCPToolProviderDTO toDto(MCPToolProvider provider, String name) {
		MCPToolProviderDTO dto = new MCPToolProviderDTO();
		dto.name = name.isEmpty() ? null : name;
		dto.description = provider.getDescription();
		dto.tools = provider.getMCPTools().stream()
				.map(spec -> spec.tool().name())
				.sorted()
				.toArray(String[]::new);
		return dto;
	}

	private static MCPToolDTO toDto(MCPTool tool) {
		MCPToolDTO dto = new MCPToolDTO();
		dto.name = tool.getName();
		dto.description = tool.getDescription();
		return dto;
	}

	private void bump() {
		ServiceRegistration<MCPServiceRuntime> local = registration;
		if (local != null) {
			local.setProperties(properties());
		}
	}

	private Hashtable<String, Object> properties() {
		Hashtable<String, Object> properties = new Hashtable<>();
		properties.put(Constants.SERVICE_CHANGECOUNT, changeCount.getAndIncrement());
		properties.put(Constants.SERVICE_DESCRIPTION, "Runtime introspection of the MCP whiteboard");
		return properties;
	}
}
