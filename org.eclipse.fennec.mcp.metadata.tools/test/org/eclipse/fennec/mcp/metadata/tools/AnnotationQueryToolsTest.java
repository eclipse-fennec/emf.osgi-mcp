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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The annotation queries against a registered type-mapping family.
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
class AnnotationQueryToolsTest {

	private FindClassesByAnnotationTool findClasses;
	private FindFeaturesByAnnotationTool findFeatures;
	private FindOperationsByAnnotationTool findOperations;
	private FindClassByNameTool findClassByName;

	@BeforeEach
	void setUp() {
		MetadataWhiteboard whiteboard = MetadataServices.createWhiteboard(new CodecAspectProvider());
		whiteboard.registerPackage(TestModels.uplinkPackage());
		whiteboard.registerPackage(TestModels.gatewayPackage());

		findClasses = new FindClassesByAnnotationTool();
		findClasses.metadata = whiteboard;
		findClasses.activate();
		findFeatures = new FindFeaturesByAnnotationTool();
		findFeatures.metadata = whiteboard;
		findFeatures.activate();
		findOperations = new FindOperationsByAnnotationTool();
		findOperations.metadata = whiteboard;
		findOperations.activate();
		findClassByName = new FindClassByNameTool();
		findClassByName.metadata = whiteboard;
		findClassByName.activate();
	}

	@Test
	void omittedValueFindsTheAbstractFamilyParentNoOtherToolCanReach() {
		Map<String, Object> result = call(findClasses, Map.of(
				"annotationSource", TestModels.TYPE_MAPPING_SOURCE,
				"key", TestModels.KEY_DISCRIMINATOR_PATH));

		assertThat(map(result, "query")).containsEntry("matchedAnyValue", Boolean.TRUE);
		List<Map<String, Object>> classes = list(result, "classes");
		assertThat(classes).hasSize(1);
		assertThat(classes.get(0))
				.containsEntry("reference", TestModels.UPLINK_MESSAGE)
				.containsEntry("nsURI", TestModels.UPLINK_NS_URI)
				.containsEntry("name", "UplinkMessage")
				.containsEntry("abstract", Boolean.TRUE);
		assertThat(map(classes.get(0), "matched")).containsEntry("value", TestModels.DISCRIMINATOR_PATH);
	}

	@Test
	void omittedValueFindsEveryClaimedDiscriminator() {
		Map<String, Object> result = call(findClasses, Map.of(
				"annotationSource", TestModels.TYPE_MAPPING_SOURCE,
				"key", TestModels.KEY_DISCRIMINATOR));

		assertThat(list(result, "classes")).extracting(hit -> hit.get("reference"))
				.containsExactly(TestModels.SENSOR_A_UPLINK, TestModels.SENSOR_B_UPLINK);
		assertThat(list(result, "classes")).extracting(hit -> map(hit, "matched").get("value"))
				.containsExactly("Sensor_A", "Sensor_B");
	}

	@Test
	void aGivenValueMatchesExactlyOneSibling() {
		Map<String, Object> result = call(findClasses, Map.of(
				"annotationSource", TestModels.TYPE_MAPPING_SOURCE,
				"key", TestModels.KEY_DISCRIMINATOR,
				"value", "Sensor_A"));

		assertThat(result).containsEntry("count", 1);
		assertThat(map(result, "query")).containsEntry("matchedAnyValue", Boolean.FALSE);
		assertThat(list(result, "classes").get(0)).containsEntry("reference", TestModels.SENSOR_A_UPLINK);
	}

	@Test
	void anUnclaimedDiscriminatorValueComesBackEmpty() {
		Map<String, Object> result = call(findClasses, Map.of(
				"annotationSource", TestModels.TYPE_MAPPING_SOURCE,
				"key", TestModels.KEY_DISCRIMINATOR,
				"value", "Dragino_LSE01"));

		assertThat(result).containsEntry("count", 0);
		assertThat(list(result, "classes")).isEmpty();
	}

	@Test
	void classHitsCarryFullSuperTypeReferences() {
		Map<String, Object> result = call(findClasses, Map.of(
				"annotationSource", TestModels.TYPE_MAPPING_SOURCE,
				"key", TestModels.KEY_DISCRIMINATOR,
				"value", "Sensor_B"));

		assertThat(ToolCalls.strings(list(result, "classes").get(0), "eSuperTypes"))
				.containsExactly(TestModels.UPLINK_MESSAGE);
	}

	@Test
	void featuresAreFoundByAnnotation() {
		Map<String, Object> result = call(findFeatures, Map.of(
				"annotationSource", TestModels.CODEC_SOURCE,
				"key", "key"));

		assertThat(list(result, "features")).hasSize(1);
		assertThat(list(result, "features").get(0))
				.containsEntry("reference", TestModels.UPLINK_NS_URI + "#//DeviceInfo/deviceProfileName")
				.containsEntry("kind", "attribute")
				.containsEntry("type", "http://www.eclipse.org/emf/2002/Ecore#//EString");
	}

	@Test
	void operationsAreFoundByAnnotation() {
		Map<String, Object> result = call(findOperations, Map.of(
				"annotationSource", TestModels.DOCS_SOURCE,
				"key", "summary"));

		assertThat(list(result, "operations")).hasSize(1);
		assertThat(list(result, "operations").get(0))
				.containsEntry("reference", TestModels.UPLINK_NS_URI + "#//UplinkMessage/describe");
	}

	@Test
	void aWrongAnnotationSourceMatchesNothingRatherThanFailing() {
		Map<String, Object> result = call(findClasses, Map.of(
				"annotationSource", "http://eclipse.org/fennec/codec/codec.type." + TestModels.MAP_ID,
				"key", "typeKeyFeaturePath"));

		assertThat(result).containsEntry("count", 0);
	}

	@Test
	void classNameResolvesAcrossPackagesTheCallerNeverNamed() {
		Map<String, Object> result = call(findClassByName, Map.of("className", "Gateway"));

		assertThat(map(result, "query")).containsEntry("searchedAllPackages", Boolean.TRUE);
		assertThat(list(result, "classes")).hasSize(1);
		assertThat(list(result, "classes").get(0)).containsEntry("reference", TestModels.GATEWAY);
	}

	@Test
	void classNameCanBePinnedToOnePackage() {
		Map<String, Object> result = call(findClassByName, Map.of(
				"className", "Gateway",
				"nsURI", TestModels.UPLINK_NS_URI));

		assertThat(result).containsEntry("count", 0);
	}

	@Test
	void aMissingRequiredArgumentIsReportedToTheAgent() {
		String error = ToolCalls.callExpectingError(findClasses, Map.of("key", TestModels.KEY_DISCRIMINATOR));
		assertThat(error).contains("annotationSource");
	}
}
