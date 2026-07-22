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

import java.util.Map;

import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.emf.tools.core.Dataset;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.EcoreAuthoring;
import org.eclipse.fennec.mcp.emf.tools.core.ToolException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP authoring tool adding an {@link EEnumLiteral} to an EEnum.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
@Component(name = "AddEEnumLiteralTool", service = MCPTool.class, property = "tool.name=add_eenum_literal")
public class AddEEnumLiteralTool extends AbstractEMFTool {

	@Reference
	DatasetRegistry registry;

	@Activate
	void activate() {
		this.name = "add_eenum_literal";
		this.description = "Add a literal to an EEnum (see add_eenum). 'literal' defaults to the name.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"datasetId": { "type": "string" },
						"eenumObjectId": { "type": "string" },
						"name": { "type": "string" },
						"value": { "type": "integer" },
						"literal": { "type": "string", "description": "serialized form; defaults to name" }
					},
					"required": ["datasetId", "eenumObjectId", "name", "value"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			Dataset dataset = registry.require(sessionId(exchange), requireString(arguments, "datasetId"));
			EEnum eEnum = EcoreAuthoring.requireEEnum(dataset, requireString(arguments, "eenumObjectId"));
			Integer value = optionalInt(arguments, "value");
			if (value == null) {
				throw new ToolException("Parameter 'value' is required and must be an integer");
			}
			EEnumLiteral literal = EcoreFactory.eINSTANCE.createEEnumLiteral();
			literal.setName(requireString(arguments, "name"));
			literal.setValue(value);
			String literalString = optionalString(arguments, "literal");
			literal.setLiteral(literalString == null ? literal.getName() : literalString);
			eEnum.getELiterals().add(literal);
			String objectId = EcoreAuthoring.put(dataset, literal, registry.limits());
			return Map.of("objectId", objectId, "name", literal.getName(), "value", literal.getValue());
		});
	}
}
