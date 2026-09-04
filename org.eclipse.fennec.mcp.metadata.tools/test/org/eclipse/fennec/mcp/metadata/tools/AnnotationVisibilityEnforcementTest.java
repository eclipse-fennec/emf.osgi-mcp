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

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.fennec.mcp.metadata.tools.ToolCalls.call;
import static org.eclipse.fennec.mcp.metadata.tools.ToolCalls.callExpectingError;

import java.util.List;
import java.util.Map;

import org.eclipse.fennec.codec.metadata.provider.CodecAspectProvider;
import org.eclipse.fennec.emf.osgi.metadata.MetadataServices;
import org.eclipse.fennec.emf.osgi.metadata.MetadataWhiteboard;
import org.eclipse.fennec.mcp.api.AnnotationVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * That the annotation deny-list actually closes this bundle's paths.
 * <p>
 * These tools are otherwise unguarded by design — "query wide to locate, read
 * narrow to copy" — which is exactly why the deny-list has to be enforced here
 * as well as in {@code emf.tools}. A policy honoured by {@code describe_eclass}
 * while {@code list_annotation_sources} still enumerates the source and
 * {@code find_classes_by_annotation} still returns its values would read as
 * protection and provide none.
 *
 * @author ilenia
 * @since Sep 3, 2026
 */
class AnnotationVisibilityEnforcementTest {

	private MetadataWhiteboard whiteboard;

	@BeforeEach
	void setUp() {
		whiteboard = MetadataServices.createWhiteboard(new CodecAspectProvider());
		whiteboard.registerPackage(TestModels.uplinkPackage());
		whiteboard.registerPackage(TestModels.gatewayPackage());
	}

	@Test
	@DisplayName("list_annotation_sources omits a denied source entirely")
	void deniedSourcesAreNotEnumerated() {
		ListAnnotationSourcesTool tool = new ListAnnotationSourcesTool();
		tool.metadata = whiteboard;
		tool.visibility = denying(TestModels.TYPE_MAPPING_SOURCE);
		tool.activate();

		String json = raw(tool, Map.of());

		// Not just absent from the source list: the entry carried its keys, its hit
		// count and the namespaces it occurs in, any one of which confirms it exists.
		assertThat(json).doesNotContain(TestModels.TYPE_MAPPING_SOURCE);
		assertThat(json).doesNotContain(TestModels.KEY_DISCRIMINATOR_PATH);
	}

	@Test
	@DisplayName("list_annotation_sources keeps the sources that are not denied")
	void otherSourcesAreStillEnumerated() {
		ListAnnotationSourcesTool tool = new ListAnnotationSourcesTool();
		tool.metadata = whiteboard;
		tool.visibility = denying("http://example.org/nothing/here/*");
		tool.activate();

		assertThat(raw(tool, Map.of())).contains(TestModels.TYPE_MAPPING_SOURCE);
	}

	@Test
	@DisplayName("find_classes_by_annotation refuses a query naming a denied source")
	void findClassesRefusesADeniedSource() {
		FindClassesByAnnotationTool tool = new FindClassesByAnnotationTool();
		tool.metadata = whiteboard;
		tool.visibility = denying(TestModels.TYPE_MAPPING_SOURCE);
		tool.activate();

		// Refused, not answered empty: an empty result is indistinguishable from
		// "no class carries this", which would have the agent conclude the
		// convention is free and reuse it.
		assertThat(callExpectingError(tool, Map.of("annotationSource", TestModels.TYPE_MAPPING_SOURCE, "key",
				TestModels.KEY_DISCRIMINATOR_PATH))).contains("withheld by this deployment");
	}

	@Test
	@DisplayName("find_features_by_annotation refuses one too")
	void findFeaturesRefusesADeniedSource() {
		FindFeaturesByAnnotationTool tool = new FindFeaturesByAnnotationTool();
		tool.metadata = whiteboard;
		tool.visibility = denying("http://eclipse.org/fennec/*");
		tool.activate();

		assertThat(callExpectingError(tool, Map.of("annotationSource", TestModels.TYPE_MAPPING_SOURCE, "key",
				TestModels.KEY_DISCRIMINATOR_PATH))).contains("withheld by this deployment");
	}

	@Test
	@DisplayName("find_operations_by_annotation refuses one too")
	void findOperationsRefusesADeniedSource() {
		FindOperationsByAnnotationTool tool = new FindOperationsByAnnotationTool();
		tool.metadata = whiteboard;
		tool.visibility = denying(TestModels.TYPE_MAPPING_SOURCE);
		tool.activate();

		assertThat(callExpectingError(tool, Map.of("annotationSource", TestModels.TYPE_MAPPING_SOURCE, "key",
				TestModels.KEY_DISCRIMINATOR_PATH))).contains("withheld by this deployment");
	}

	@Test
	@DisplayName("a query for a source that is not denied still works")
	void anAllowedQueryIsUnaffected() {
		FindClassesByAnnotationTool tool = new FindClassesByAnnotationTool();
		tool.metadata = whiteboard;
		tool.visibility = denying("http://example.org/nothing/here/*");
		tool.activate();

		Map<String, Object> result = call(tool, Map.of("annotationSource", TestModels.TYPE_MAPPING_SOURCE, "key",
				TestModels.KEY_DISCRIMINATOR_PATH));

		assertThat(result).doesNotContainKey("error");
		assertThat((List<?>) result.get("classes")).isNotEmpty();
	}

	@Test
	@DisplayName("describe_aspects withholds a denied aspect type, asked for by name or not")
	void deniedAspectTypesAreWithheld() {
		DescribeAspectsTool tool = new DescribeAspectsTool();
		tool.metadata = whiteboard;
		tool.visibility = AnnotationVisibility.denying(List.of(), List.of("codec"));
		tool.activate();

		// An aspect is the parsed form of annotations and carries no source, so the
		// source list cannot reach it: a 'codec' aspect is the class's whole
		// serialization configuration.
		Map<String, Object> byName = call(tool,
				Map.of("element", TestModels.UPLINK_MESSAGE, "aspectTypeId", "codec"));
		assertThat(byName).containsEntry("count", 0);

		Map<String, Object> all = call(tool, Map.of("element", TestModels.UPLINK_MESSAGE));
		assertThat(all).containsEntry("count", 0);
	}

	@Test
	@DisplayName("the 'no such aspect' hint does not name a denied type either")
	void theAbsenceHintDoesNotLeakDeniedTypes() {
		DescribeAspectsTool tool = new DescribeAspectsTool();
		tool.metadata = whiteboard;
		tool.visibility = AnnotationVisibility.denying(List.of(), List.of("codec"));
		tool.activate();

		// The hint lists which type ids the element does carry, and fires exactly
		// when a query returned nothing - which is what a denied type looks like
		// from the outside.
		String json = raw(tool, Map.of("element", TestModels.UPLINK_MESSAGE, "aspectTypeId", "nope"));

		assertThat(json).doesNotContain("codec");
	}

	@Test
	@DisplayName("list_aspects omits a denied aspect type")
	void listAspectsOmitsDeniedTypes() {
		ListAspectsTool tool = new ListAspectsTool();
		tool.metadata = whiteboard;
		tool.visibility = AnnotationVisibility.denying(List.of(), List.of("codec"));
		tool.activate();

		assertThat(raw(tool, Map.of())).doesNotContain("codec");
	}

	@Test
	@DisplayName("describe_metadata_status omits a denied aspect type from its inventory")
	void statusOmitsDeniedTypes() {
		DescribeMetadataStatusTool tool = new DescribeMetadataStatusTool();
		tool.metadata = whiteboard;
		tool.visibility = AnnotationVisibility.denying(List.of(), List.of("codec"));
		tool.activate();

		// This tool reports 'aspectTypeIds' across the whole runtime - a path the
		// initial survey missed and the compiler surfaced.
		assertThat(raw(tool, Map.of())).doesNotContain("codec");
	}

	private static AnnotationVisibility denying(String... sourcePatterns) {
		return AnnotationVisibility.denying(List.of(sourcePatterns), List.of());
	}

	/**
	 * The whole rendered result as text. A withheld source or type must not appear
	 * <em>anywhere</em> in the response — not in a key list, a hit count, a hint
	 * or a namespace — so these assertions search the raw document rather than one
	 * field of the parsed map.
	 */
	private static String raw(AbstractMetadataTool tool, Map<String, Object> arguments) {
		McpSchema.CallToolResult result = tool.execute(null, arguments).block();
		assertThat(result).isNotNull();
		String text = ((McpSchema.TextContent) result.content().get(0)).text();
		assertThat(result.isError()).as("tool error: %s", text).isNotEqualTo(Boolean.TRUE);
		return text;
	}
}
