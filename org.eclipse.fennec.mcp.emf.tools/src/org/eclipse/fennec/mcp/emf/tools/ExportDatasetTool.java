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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.emf.tools.core.Dataset;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.Exports;
import org.eclipse.fennec.mcp.emf.tools.core.ToolException;
import org.eclipse.fennec.mcp.emf.tools.core.ValidationReports;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP tool serializing a dataset. Format {@code xmi} (default) returns one
 * XMI document containing all root objects; format {@code json} returns one
 * JSON object per root (via the Fennec codec). Content is returned inline
 * only up to the configured byte cap — larger exports return a descriptor
 * (size, counts, export URI) instead, protecting the agent's context and the
 * transport.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
@Component(name = "ExportDatasetTool", service = MCPTool.class, property = "tool.name=export_dataset")
public class ExportDatasetTool extends AbstractEMFTool {

	@Reference
	DatasetRegistry registry;

	@Activate
	void activate() {
		this.name = "export_dataset";
		this.description = "Serialize all root objects of a dataset. Format 'xmi' (default) returns one " +
				"XMI document, 'json' returns one JSON object per root. Set validate=true to include a " +
				"validation report. Exports larger than the configured inline cap return a descriptor " +
				"instead of the content.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"datasetId": {
							"type": "string",
							"description": "The dataset to export"
						},
						"format": {
							"type": "string",
							"enum": ["xmi", "json"],
							"description": "The serialization format (default xmi)"
						},
						"validate": {
							"type": "boolean",
							"description": "Include a validation report in the response (default true)"
						}
					},
					"required": ["datasetId"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			Dataset dataset = registry.require(sessionId(exchange), requireString(arguments, "datasetId"));
			String format = optionalString(arguments, "format");
			if (format == null) {
				format = "xmi";
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("datasetId", dataset.getId());
			result.put("format", format);
			result.put("rootCount", dataset.roots().size());
			result.put("eClassCounts", Exports.eClassCounts(dataset));
			if (optionalBoolean(arguments, "validate", true)) {
				result.put("validation", ValidationReports.validate(dataset));
			}
			String content = switch (format) {
			case "xmi" -> Exports.toXmi(dataset);
			case "json" -> jsonContent(dataset);
			default -> throw new ToolException(String.format("Unknown format '%s'. Use 'xmi' or 'json'", format));
			};
			int byteSize = content.getBytes(StandardCharsets.UTF_8).length;
			result.put("byteSize", byteSize);
			int cap = registry.limits().maxInlineExportBytes();
			if (byteSize <= cap) {
				result.put("content", content);
			} else {
				result.put("inline", false);
				result.put("resourceUri", Exports.exportUri(dataset, format));
				result.put("note", String.format(
						"Export of %d bytes exceeds the inline cap of %d bytes. Reduce the dataset, "
								+ "or raise max.inline.export.bytes in the EMFDatasetRegistry configuration.", byteSize, cap));
			}
			return result;
		});
	}

	private String jsonContent(Dataset dataset) throws Exception {
		List<EObject> roots = dataset.roots();
		if (roots.isEmpty()) {
			throw new ToolException(String.format("Dataset '%s' has no root objects to serialize", dataset.getId()));
		}
		try {
			List<Map<String, Object>> serialized = new ArrayList<>();
			for (EObject root : roots) {
				serialized.add(saveEObjectToString(root, dataset.getResourceSet()));
			}
			return MAPPER.writeValueAsString(serialized);
		} catch (RuntimeException e) {
			throw new ToolException("JSON export requires the Fennec codec to be installed; use format 'xmi' instead");
		}
	}
}
