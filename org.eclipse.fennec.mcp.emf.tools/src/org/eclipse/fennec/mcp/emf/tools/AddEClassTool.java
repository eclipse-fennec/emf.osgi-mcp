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

import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.emf.tools.core.ClassifierResolver;
import org.eclipse.fennec.mcp.emf.tools.core.Dataset;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.EcoreAuthoring;
import org.eclipse.fennec.mcp.emf.tools.core.ModelGuard;
import org.eclipse.fennec.mcp.emf.tools.core.PackageLocalResolver;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP authoring tool adding an {@link EClass} to an EPackage. Long-tail
 * properties not covered here (e.g. changing name later) are settable via
 * modify_feature on the returned objectId; generics via add_etypeparameter.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
@Component(name = "AddEClassTool", service = MCPTool.class, property = "tool.name=add_eclass")
public class AddEClassTool extends AbstractEMFTool {

	@Reference
	DatasetRegistry registry;
	@Reference
	ModelGuard guard;

	@Activate
	void activate() {
		this.name = "add_eclass";
		this.description = "Add an EClass to a package (metamodel authoring). Declare its features and annotations "
				+ "inline via 'eAttributes'/'eReferences'/'eAnnotations' — one call instead of waiting for the class "
				+ "objectId and then issuing one call per feature. Super types and feature types are references to "
				+ "other classifiers: a <nsURI>#//<Name> identifier, '#//<Name>' for a sibling classifier of the same "
				+ "package, or the objectId of a class authored in the same dataset. Returns the new class objectId "
				+ "and the objectId of every nested element; add_eattribute/add_ereference/add_eoperation still work "
				+ "for adding to an existing class.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"datasetId": { "type": "string" },
						"packageObjectId": { "type": "string", "description": "objectId of the owning EPackage (see create_epackage)" },
						"name": { "type": "string" },
						"abstract": { "type": "boolean", "description": "default false" },
						"interface": { "type": "boolean", "description": "default false (implies abstract)" },
						"eSuperTypes": { "type": "array", "items": { "type": "string" }, "description": "class refs (<nsURI>#//<Name> or #//<Name> for a sibling) or dataset objectIds" },
						"eGenericSuperTypes": { "type": "array", "items": { "type": "object" }, "description": "generic super type specs (parameterized, e.g. Bar<T>)" },
						"eAttributes": { "type": "array", "items": { "type": "object" }, "description": "attributes to create on this class; each entry takes the add_eattribute arguments except datasetId/classObjectId" },
						"eReferences": { "type": "array", "items": { "type": "object" }, "description": "references to create on this class; each entry takes the add_ereference arguments except datasetId/classObjectId" },
						"eAnnotations": { "type": "array", "items": { "type": "object" }, "description": "annotations to put on this class; each entry takes the add_eannotation arguments except datasetId/targetObjectId" }
					},
					"required": ["datasetId", "packageObjectId", "name"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			String sessionId = sessionId(exchange);
			Dataset dataset = registry.require(sessionId, requireString(arguments, "datasetId"));
			EPackage ePackage = EcoreAuthoring.requireEPackage(dataset, requireString(arguments, "packageObjectId"));
			EClass eClass = EcoreFactory.eINSTANCE.createEClass();
			eClass.setName(requireString(arguments, "name"));
			boolean isInterface = optionalBoolean(arguments, "interface", false);
			eClass.setInterface(isInterface);
			eClass.setAbstract(isInterface || optionalBoolean(arguments, "abstract", false));
			// sibling classifiers of the package, and the new class itself, resolve
			// locally: the package is not registered yet, so the registry cannot see them
			ClassifierResolver resolver = new PackageLocalResolver(guard.resolverFor(sessionId), ePackage, List.of(eClass));
			EcoreAuthoring.addSuperTypes(dataset, eClass, optionalStringList(arguments, "eSuperTypes"), resolver);
			NestedAuthoring.applyGenericSuperTypes(dataset, eClass, arguments.get("eGenericSuperTypes"), resolver);
			NestedAuthoring.applyClassChildren(dataset, eClass, arguments, resolver);
			// nothing touched the dataset until here, so a failure above leaves it unchanged
			ePackage.getEClassifiers().add(eClass);
			Map<String, EObject> indexed = EcoreAuthoring.indexTreeDetailed(dataset, eClass, registry.limits(), EcoreAuthoring.ADDRESSABLE);
			String objectId = indexed.keySet().iterator().next();
			return Map.of("objectId", objectId, "eClass", ModelGuard.refOf(eClass),
					"created", NestedAuthoring.describe(indexed), "objectCount", dataset.objectCount());
		});
	}
}
