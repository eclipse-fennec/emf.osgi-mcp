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

import org.eclipse.emf.ecore.EClass;
import org.eclipse.fennec.mcp.api.AnnotationVisibility;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.emf.tools.core.EClassDescriber;
import org.eclipse.fennec.mcp.emf.tools.core.ModelGuard;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP tool describing an allow-listed EClass: its settable structural
 * features with kind (attribute/containment/reference), type, multiplicity,
 * defaults, enum literals and documentation.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
@Component(name = "DescribeEClassTool", service = MCPTool.class, property = "tool.name=describe_eclass")
public class DescribeEClassTool extends AbstractEMFTool {

	@Reference
	ModelGuard guard;
	@Reference
	AnnotationVisibility visibility;

	@Activate
	void activate() {
		this.name = "describe_eclass";
		this.description = "Describe an EClass: all settable features with their kind " +
				"(attribute, containment, reference), type, multiplicity, required flag, " +
				"default values and enum literals. Use this before building instances with " +
				"create_instance and modify_feature. It also reports what you need in order to " +
				"copy a model rather than instantiate it: the EAnnotations of the class and of " +
				"every feature in their exact source and key spelling (ExtendedMetaData wire " +
				"names included), 'superTypes' as declared against 'allSuperTypes' inherited and " +
				"both as <nsURI>#//<Name>, and 'inherited'/'declaringClass' on a feature that " +
				"comes from a supertype so you do not re-declare it. Abstract classes and " +
				"interfaces are described too — reading one is not instantiating it — so this, " +
				"not export_package, is the way to read the conventions of a base class.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"eClass": {
							"type": "string",
							"description": "The EClass identifier of the form <nsURI>#//<ClassName>, e.g. 'http://example.org/library#//Book'"
						}
					},
					"required": ["eClass"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			EClass eClass = guard.requireAllowedEClassForRead(requireString(arguments, "eClass"));
			return EClassDescriber.describe(eClass, guard, visibility);
		});
	}
}
