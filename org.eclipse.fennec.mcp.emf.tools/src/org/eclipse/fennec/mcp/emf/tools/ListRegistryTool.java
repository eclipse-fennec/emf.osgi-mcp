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
package org.eclipse.fennec.mcp.emf.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.emf.tools.core.PackageRegistry;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP tool listing the packages registered in the current session, with their
 * instantiable classes.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
@Component(name = "ListRegistryTool", service = MCPTool.class, property = "tool.name=list_registry")
public class ListRegistryTool extends AbstractEMFTool {

	@Reference
	PackageRegistry packages;

	@Activate
	void activate() {
		this.name = "list_registry";
		this.description = "List the packages registered in this session (authored or imported), each with its "
				+ "concrete instantiable classes as <nsURI>#//<ClassName> references.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {}
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			List<Map<String, Object>> registered = packages.list(sessionId(exchange)).stream()
					.map(ePackage -> {
						Map<String, Object> entry = new LinkedHashMap<String, Object>();
						entry.put("name", ePackage.getName());
						entry.put("nsURI", ePackage.getNsURI());
						List<String> classes = ePackage.getEClassifiers().stream()
								.filter(EClass.class::isInstance)
								.map(EClass.class::cast)
								.filter(c -> !c.isAbstract() && !c.isInterface())
								.map(c -> ePackage.getNsURI() + "#//" + c.getName())
								.toList();
						entry.put("instantiableClasses", classes);
						return entry;
					})
					.toList();
			return Map.of("packages", registered);
		});
	}
}
