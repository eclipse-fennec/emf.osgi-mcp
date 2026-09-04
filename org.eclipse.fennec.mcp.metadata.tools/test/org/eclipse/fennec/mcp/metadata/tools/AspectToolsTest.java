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
import static org.eclipse.fennec.mcp.metadata.tools.ToolCalls.list;
import static org.eclipse.fennec.mcp.metadata.tools.ToolCalls.map;

import java.util.List;
import java.util.Map;

import org.eclipse.fennec.codec.metadata.provider.CodecAspectProvider;
import org.eclipse.fennec.emf.osgi.metadata.MetadataServices;
import org.eclipse.fennec.emf.osgi.metadata.MetadataWhiteboard;
import org.eclipse.fennec.mcp.api.AnnotationVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The generic aspect and annotation-vocabulary tools. These are what let an
 * agent start from nothing: it learns the annotation source and detail keys of
 * this runtime rather than having to know them, and reads a sibling class's
 * parsed configuration without the tool knowing what a codec is.
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
class AspectToolsTest {

	private ListAspectsTool listAspects;
	private DescribeAspectsTool describeAspects;
	private ListAnnotationSourcesTool listAnnotationSources;

	@BeforeEach
	void setUp() {
		MetadataWhiteboard whiteboard = MetadataServices.createWhiteboard(new CodecAspectProvider());
		whiteboard.registerPackage(TestModels.uplinkPackage());
		whiteboard.registerPackage(TestModels.gatewayPackage());

		listAspects = new ListAspectsTool();
		listAspects.metadata = whiteboard;
		listAspects.visibility = AnnotationVisibility.unrestricted();
		listAspects.activate();
		describeAspects = new DescribeAspectsTool();
		describeAspects.metadata = whiteboard;
		describeAspects.visibility = AnnotationVisibility.unrestricted();
		describeAspects.activate();
		listAnnotationSources = new ListAnnotationSourcesTool();
		listAnnotationSources.metadata = whiteboard;
		listAnnotationSources.visibility = AnnotationVisibility.unrestricted();
		listAnnotationSources.activate();
	}

	@Test
	void theAnnotationVocabularyIsDiscoverableWithoutKnowingAnyString() {
		Map<String, Object> result = call(listAnnotationSources, Map.of());

		Map<String, Object> typeMapping = source(result, TestModels.TYPE_MAPPING_SOURCE);
		assertThat(ToolCalls.strings(typeMapping, "keys"))
				.containsExactly(TestModels.KEY_DISCRIMINATOR, TestModels.KEY_DISCRIMINATOR_PATH);
		assertThat(typeMapping).containsEntry("hits", 3);
		assertThat(ToolCalls.strings(typeMapping, "nsURIs")).containsExactly(TestModels.UPLINK_NS_URI);
		assertThat(map(typeMapping, "elementKinds")).containsEntry("class", 3);
	}

	@Test
	void theScanCanBePinnedToOnePackage() {
		Map<String, Object> result = call(listAnnotationSources, Map.of("nsURI", TestModels.GATEWAY_NS_URI));

		assertThat(result).containsEntry("scannedPackageVersions", 1);
		assertThat(list(result, "annotationSources")).isEmpty();
	}

	@Test
	void anUnknownNamespaceIsAnActionableError() {
		String error = ToolCalls.callExpectingError(listAnnotationSources, Map.of("nsURI", "https://example.org/nope"));
		assertThat(error).contains("describe_metadata_status");
	}

	@Test
	void aspectTypeIdsArePresentedGenerically() {
		Map<String, Object> result = call(listAspects, Map.of());

		List<Map<String, Object>> aspects = list(result, "aspects");
		assertThat(aspects).extracting(aspect -> aspect.get("aspectTypeId")).contains("codec");
		Map<String, Object> codec = aspects.stream()
				.filter(aspect -> "codec".equals(aspect.get("aspectTypeId"))).findFirst().orElseThrow();
		assertThat(map(codec, "elementKinds")).containsKeys("package", "class", "feature");
	}

	@Test
	void aSiblingsParsedConfigurationIsReadableByReference() {
		Map<String, Object> result = call(describeAspects, Map.of(
				"element", TestModels.SENSOR_A_UPLINK,
				"aspectTypeId", "codec"));

		assertThat(result).containsEntry("kind", "class");
		assertThat(map(result, "resolved")).containsEntry("reference", TestModels.SENSOR_A_UPLINK);

		List<Map<String, Object>> aspects = list(result, "aspects");
		assertThat(aspects).hasSize(1);
		Map<String, Object> content = map(aspects.get(0), "content");
		assertThat(content).containsEntry("discriminatorValue", "Sensor_A");
		assertThat(map(content, "typeConfig")).containsEntry("mapId", TestModels.MAP_ID);
	}

	@Test
	void everyEntryCarriesItsDiagnosticsField() {
		Map<String, Object> result = call(describeAspects, Map.of("element", TestModels.UPLINK_MESSAGE));

		assertThat(list(result, "aspects")).isNotEmpty();
		assertThat(list(result, "aspects")).allSatisfy(aspect -> assertThat(aspect).containsKey("diagnostics"));
	}

	@Test
	void theAbstractParentsDiscriminatorPathIsReadableThroughItsAspect() {
		Map<String, Object> result = call(describeAspects, Map.of(
				"element", TestModels.UPLINK_MESSAGE,
				"aspectTypeId", "codec"));

		Map<String, Object> content = map(list(result, "aspects").get(0), "content");
		assertThat(map(content, "typeConfig"))
				.containsEntry("mapId", TestModels.MAP_ID)
				.containsEntry("discriminatorPath", TestModels.DISCRIMINATOR_PATH);
	}

	@Test
	void aPackageAndAMemberAreAddressableToo() {
		Map<String, Object> packageAspects = call(describeAspects, Map.of("element", TestModels.UPLINK_NS_URI));
		assertThat(packageAspects).containsEntry("kind", "package");
		assertThat(map(packageAspects, "resolved")).containsEntry("nsURI", TestModels.UPLINK_NS_URI);

		Map<String, Object> featureAspects = call(describeAspects,
				Map.of("element", TestModels.UPLINK_NS_URI + "#//DeviceInfo/deviceProfileName"));
		assertThat(featureAspects).containsEntry("kind", "feature");

		Map<String, Object> operationAspects = call(describeAspects,
				Map.of("element", TestModels.UPLINK_MESSAGE + "/describe"));
		assertThat(operationAspects).containsEntry("kind", "operation");
	}

	@Test
	void anAbsentAspectTypeSaysWhichOnesTheElementDoesCarry() {
		Map<String, Object> result = call(describeAspects, Map.of(
				"element", TestModels.SENSOR_A_UPLINK,
				"aspectTypeId", "persistence"));

		assertThat(result).containsEntry("count", 0);
		assertThat(String.valueOf(result.get("note"))).contains("codec");
	}

	@Test
	void anUnresolvableElementPointsAtTheToolThatWouldFindIt() {
		String error = ToolCalls.callExpectingError(describeAspects,
				Map.of("element", TestModels.UPLINK_NS_URI + "#//Nope"));
		assertThat(error).contains("find_class_by_name");
	}

	private static Map<String, Object> source(Map<String, Object> result, String annotationSource) {
		return list(result, "annotationSources").stream()
				.filter(entry -> annotationSource.equals(entry.get("annotationSource")))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no such annotation source: " + annotationSource));
	}
}
