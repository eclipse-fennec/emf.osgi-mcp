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
package org.eclipse.fennec.mcp.api;

import java.util.Map;

import org.osgi.annotation.versioning.ProviderType;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;


/**
 * Represents a single MCP tool that can be registered as an OSGi service
 * and discovered via the whiteboard pattern by {@link MCPToolProvider}.
 * <p>
 * Each tool defines a name, description, input/output JSON schemas, and
 * an async execution method. Tool implementations should extend
 * {@link AbstractMCPTool} for schema loading utilities.
 *
 * @author ilenia
 * @since Jan 23, 2026
 */
@ProviderType
public interface MCPTool {
	
	/**
	 * @return the name of the tool
	 */
	String getName();    
	
    /**
     * @return the description of the tool
     */
    String getDescription();              
    
    /**
     * @return the string input schema for the tool
     */
    String getInputSchema();                       
    
    /**
     * @return the string output schema for the tool, when applicable. Null otherwise.
     */
    String getOutputSchema();  
    
    
    /**
     * Construct the actual tool operation
     * @param exchange
     * @param arguments
     * @return
     */
    Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments);   

}
