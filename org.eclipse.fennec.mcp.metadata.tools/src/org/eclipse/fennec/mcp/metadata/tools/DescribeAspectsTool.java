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
package org.eclipse.fennec.mcp.metadata.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.EList;
import org.eclipse.fennec.emf.osgi.metadata.MetadataService;
import org.eclipse.fennec.emf.osgi.model.metadata.AspectEntry;
import org.eclipse.fennec.emf.osgi.model.metadata.ClassMetadata;
import org.eclipse.fennec.emf.osgi.model.metadata.FeatureMetadata;
import org.eclipse.fennec.emf.osgi.model.metadata.OperationMetadata;
import org.eclipse.fennec.emf.osgi.model.metadata.PackageMetadata;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.metadata.tools.core.AspectRenderer;
import org.eclipse.fennec.mcp.metadata.tools.core.ElementReference;
import org.eclipse.fennec.mcp.metadata.tools.core.MetadataViews;
import org.eclipse.fennec.mcp.metadata.tools.core.ToolException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP tool reading the parsed aspect entries attached to one element of the
 * metadata layer - a package, a class, a structural feature or an operation.
 * <p>
 * Generic by design: aspect contents are rendered by walking their EClass, so
 * this works for aspect types that did not exist when the tool was written. The
 * {@code diagnostics} of each entry are always returned, because that is where a
 * provider records that an aspect <em>failed to build</em> - a misplaced or
 * misspelled annotation is reported there and nowhere else.
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
@Component(name = "DescribeAspectsTool", service = MCPTool.class, property = "tool.name=describe_aspects")
public class DescribeAspectsTool extends AbstractMetadataTool {

	@Reference
	MetadataService metadata;

	@Activate
	void activate() {
		this.name = "describe_aspects";
		this.description = "Read the parsed aspect entries of one metadata element: a package ('<nsURI>'), a "
				+ "class ('<nsURI>#//<Name>') or a member ('<nsURI>#//<Name>/<featureOrOperation>'). Each entry "
				+ "returns its type id, its parsed content and its DIAGNOSTICS - the diagnostics are how a "
				+ "provider reports that an aspect failed to build, e.g. an annotation placed on the wrong kind "
				+ "of element, which is otherwise silent. For a 'codec' aspect the content is the class's "
				+ "serialization configuration (mapId, discriminator value, inheritance and strictness flags), "
				+ "so this is the tool that shows how a sibling class is wired up. Omit 'aspectTypeId' for all "
				+ "aspects on the element; list_aspects shows which type ids exist here.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"element": {
							"type": "string",
							"description": "The element to read: '<nsURI>' for a package, '<nsURI>#//<ClassName>' for a class, or '<nsURI>#//<ClassName>/<featureOrOperation>' for a member."
						},
						"aspectTypeId": {
							"type": "string",
							"description": "Optional. Return only entries of this aspect type, e.g. 'codec'. Omit for all of them."
						}
					},
					"required": ["element"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			ElementReference element = ElementReference.parse(requireString(arguments, "element"));
			String aspectTypeId = optionalString(arguments, "aspectTypeId");

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("element", element.reference());
			result.put("aspectTypeId", aspectTypeId);

			EList<AspectEntry> aspects = switch (element.kind()) {
				case PACKAGE -> describePackage(element, result);
				case CLASS -> describeClass(element, result);
				case MEMBER -> describeMember(element, result);
			};

			List<Map<String, Object>> rendered = AspectRenderer.render(aspects, aspectTypeId);
			result.put("count", rendered.size());
			result.put("aspects", rendered);
			if (rendered.isEmpty()) {
				result.put("note", available(aspects, aspectTypeId));
			}
			return result;
		});
	}

	private EList<AspectEntry> describePackage(ElementReference element, Map<String, Object> result) {
		PackageMetadata packageMetadata = metadata.getPackageMetadata(element.nsURI())
				.orElseThrow(() -> new ToolException(String.format(
						"No package is registered under namespace '%s'. Call describe_metadata_status to see "
								+ "which namespaces are known to this runtime.", element.nsURI())));
		result.put("kind", "package");
		result.put("resolved", packageView(packageMetadata));
		return packageMetadata.getAspects();
	}

	private EList<AspectEntry> describeClass(ElementReference element, Map<String, Object> result) {
		ClassMetadata classMetadata = requireClass(element);
		result.put("kind", "class");
		result.put("resolved", MetadataViews.classHit(classMetadata));
		return classMetadata.getAspects();
	}

	private EList<AspectEntry> describeMember(ElementReference element, Map<String, Object> result) {
		ClassMetadata classMetadata = requireClass(element);
		for (FeatureMetadata feature : classMetadata.getFeatures()) {
			if (element.memberName().equals(feature.getName())) {
				result.put("kind", "feature");
				result.put("resolved", MetadataViews.featureHit(feature));
				return feature.getAspects();
			}
		}
		for (OperationMetadata operation : classMetadata.getOperations()) {
			if (element.memberName().equals(operation.getName())) {
				result.put("kind", "operation");
				result.put("resolved", MetadataViews.operationHit(operation));
				return operation.getAspects();
			}
		}
		throw new ToolException(String.format(
				"Class '%s' has no feature or operation named '%s'. Use describe_eclass for the feature list.",
				MetadataViews.classReference(classMetadata), element.memberName()));
	}

	private ClassMetadata requireClass(ElementReference element) {
		return MetadataViews.requireIndex(metadata)
				.findByClassName(element.nsURI(), element.className())
				.orElseThrow(() -> new ToolException(String.format(
						"No class '%s' is registered under namespace '%s'. Use find_class_by_name to locate it "
								+ "across packages.", element.className(), element.nsURI())));
	}

	private static Map<String, Object> packageView(PackageMetadata packageMetadata) {
		Map<String, Object> view = new LinkedHashMap<>();
		view.put("nsURI", packageMetadata.getNsURI());
		view.put("modelFingerprint", packageMetadata.getModelFingerprint());
		view.put("origin", MetadataViews.origin(packageMetadata));
		return view;
	}

	/**
	 * An element with no matching aspect entry is a normal answer, but an agent
	 * cannot tell "this element carries no such aspect" from "no provider for that
	 * aspect is deployed" - so say which type ids the element does carry.
	 */
	private static String available(EList<AspectEntry> aspects, String aspectTypeId) {
		if (aspects == null || aspects.isEmpty()) {
			return "This element carries no aspect entries at all. Aspects are contributed by MetadataHandler "
					+ "providers; call list_aspects to see whether any are deployed in this runtime.";
		}
		List<String> typeIds = aspects.stream().map(AspectEntry::getTypeId).distinct().sorted().toList();
		return String.format("This element carries no '%s' aspect. Available aspect type ids here: %s",
				aspectTypeId, String.join(", ", typeIds));
	}
}
