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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.fennec.mcp.api.MCPServerConstants;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.api.MCPToolProvider;
import org.osgi.annotation.bundle.Capability;
import org.osgi.namespace.implementation.ImplementationNamespace;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.Designate;

import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * DS component implementing the {@link MCPToolProvider} whiteboard aggregator.
 * Collects all {@link MCPTool} services matching the configured LDAP target filter
 * and converts them into MCP SDK {@link AsyncToolSpecification} objects with
 * reactive call handlers.
 * <p>
 * Each tool execution is wrapped in a {@link Mono} with a 1-minute timeout,
 * scheduled on a bounded-elastic thread pool. Execution metadata (arguments,
 * timing, session ID) is logged to stderr for monitoring.
 * <p>
 * Requires factory configuration (PID: {@code MCPToolProvider}) specifying
 * at minimum the tool target filter and expected cardinality.
 *
 * @author ilenia
 * @since Jan 23, 2026
 */
@Component(name = "MCPToolProvider", service = {MCPToolProvider.class}, 
configurationPid = "MCPToolProvider", configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = MCPToolProviderConfig.class)
@Capability(namespace = ImplementationNamespace.IMPLEMENTATION_NAMESPACE, name = MCPServerConstants.MCP_TOOL_PROVIDER_IMPLEMENTATION, version = MCPServerConstants.MCP_TOOL_PROVIDER_VERSION)
public class MCPToolProviderImpl implements MCPToolProvider{

	private static final Logger LOGGER = Logger.getLogger(MCPToolProviderImpl.class.getName());

	private String description;
	
	@Reference
	private McpJsonMapperSupplier jsonMapper;
	private final List<MCPTool> tools = new CopyOnWriteArrayList<>();
	private volatile Runnable changeListener;

	@Reference(name = "tools", cardinality = ReferenceCardinality.AT_LEAST_ONE, policy = ReferencePolicy.DYNAMIC)
	void addTool(MCPTool tool) {
		tools.add(tool);
		notifyChanged();
	}

	void removeTool(MCPTool tool) {
		tools.remove(tool);
		notifyChanged();
	}

	@Override
	public void onToolsChanged(Runnable listener) {
		this.changeListener = listener;
	}

	private void notifyChanged() {
		Runnable listener = changeListener;
		if (listener != null) {
			listener.run();
		}
	}

	@Activate
	public MCPToolProviderImpl(MCPToolProviderConfig config) {
		this.description = config.description();
	}


	/* 
	 * (non-Javadoc)
	 * @see org.eclipse.fennec.ai.mcp.api.MCPToolProvider#getMCPTools()
	 */
	@Override
	public List<AsyncToolSpecification> getMCPTools() {
		return tools.stream()                                                                                                                             
				.map(this::toAsyncToolSpecification)                                                                                                          
				.toList();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.eclipse.fennec.ai.mcp.api.MCPToolProvider#getDescription()
	 */
	@Override
	public String getDescription() {
		return description;
	}

	/**
	 * Converts a single {@link MCPTool} into an MCP SDK {@link AsyncToolSpecification}.
	 * Builds the tool definition (name, description, input/output schemas) and wraps
	 * the tool's execute method in a reactive handler with timeout, scheduling, and
	 * debug logging of request metadata and execution duration.
	 */
	private AsyncToolSpecification toAsyncToolSpecification(MCPTool tool) {
		Tool.Builder builder = Tool.builder(tool.getName(), new JacksonMcpJsonMapper(new JsonMapper()), tool.getInputSchema())
				.description(tool.getDescription());
		
		if (tool.getOutputSchema() != null) {                                                                                                             
			builder.outputSchema(new JacksonMcpJsonMapper(new JsonMapper()), tool.getOutputSchema());                                                   
		}                       
		BiFunction<McpAsyncServerExchange, CallToolRequest, Mono<McpSchema.CallToolResult>> handler =          
				(exchange, request) -> {
			        long startTime = System.currentTimeMillis();
			        return Mono.defer(() -> tool.execute(exchange, request.arguments()))
			        	.timeout(Duration.ofMinutes(1))
			            .subscribeOn(Schedulers.boundedElastic())
			            .doOnSuccess(result -> {
			                long duration = System.currentTimeMillis() - startTime;
			                if(request.meta() != null) {
			                	 request.meta().forEach((k,v) -> {
					                	LOGGER.fine(() -> String.format("Tool [%s] meta %s = %s", request.name(), k, v));
					                });
			                }			               
			                if(request.arguments() != null && !request.arguments().isEmpty()) {
			                	request.arguments().forEach((k,v) -> {
			                		LOGGER.fine(() -> String.format("Tool [%s] param %s = %s", request.name(), k, v));
			                	});
			                }
			                LOGGER.fine(String.format("Tool [%s] completed in %dms for session id %s", request.name(), duration, exchange.sessionId()));
			            })
			            .doOnError(e -> LOGGER.log(Level.FINE,
			                    String.format("Tool [%s] FAILED for session id %s: %s", request.name(), exchange.sessionId(), e.getMessage()), e))
			            .onErrorResume(Mono::error);
			    };

				return McpServerFeatures.AsyncToolSpecification.builder()                                                                                         
						.tool(builder.build())                                                                                                                        
						.callHandler(handler)      
	
						.build();                                                                                                                                     
	}                             
}
