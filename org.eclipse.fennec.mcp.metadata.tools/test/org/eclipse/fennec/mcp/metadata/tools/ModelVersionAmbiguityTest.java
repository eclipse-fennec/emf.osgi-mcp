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
import static org.eclipse.fennec.mcp.metadata.tools.ToolCalls.list;
import static org.eclipse.fennec.mcp.metadata.tools.ToolCalls.strings;

import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EPackage;
import org.eclipse.fennec.codec.metadata.provider.CodecAspectProvider;
import org.eclipse.fennec.emf.osgi.metadata.MetadataServices;
import org.eclipse.fennec.emf.osgi.metadata.MetadataWhiteboard;
import org.eclipse.fennec.emf.osgi.model.metadata.PackageMetadata;
import org.eclipse.fennec.mcp.api.AnnotationVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Two diverging model versions registered under one namespace - the shape an
 * Atlas produces when a model is staged twice.
 * <p>
 * The rule under test is uniform across the bundle: an nsURI that holds more
 * than one version is <b>refused</b>, never resolved to the most recently
 * registered one. A silently chosen version is the dangerous failure here,
 * because the payload that comes back is well-formed, plausible, and about a
 * model the caller did not ask for - a GDPR finding attributed to the wrong
 * stage reads exactly like a correct one.
 *
 * @author ilenia
 * @since Sep 11, 2026
 */
class ModelVersionAmbiguityTest {

	private MetadataWhiteboard whiteboard;
	private String firstFingerprint;
	private String secondFingerprint;

	private DescribePackageMetadataTool describePackage;
	private DescribeMetadataStatusTool status;
	private DescribeAspectsTool describeAspects;
	private FindClassByNameTool findClassByName;
	private FindClassesByAnnotationTool findClasses;
	private ListAnnotationSourcesTool listSources;

	@BeforeEach
	void setUp() {
		whiteboard = MetadataServices.createWhiteboard(new CodecAspectProvider());
		firstFingerprint = register(TestModels.uplinkPackage());
		secondFingerprint = register(TestModels.divergedUplinkPackage());
		whiteboard.registerPackage(TestModels.gatewayPackage());

		describePackage = new DescribePackageMetadataTool();
		describePackage.metadata = whiteboard;
		describePackage.activate();
		status = new DescribeMetadataStatusTool();
		status.metadata = whiteboard;
		status.visibility = AnnotationVisibility.unrestricted();
		status.activate();
		describeAspects = new DescribeAspectsTool();
		describeAspects.metadata = whiteboard;
		describeAspects.visibility = AnnotationVisibility.unrestricted();
		describeAspects.activate();
		findClassByName = new FindClassByNameTool();
		findClassByName.metadata = whiteboard;
		findClassByName.activate();
		findClasses = new FindClassesByAnnotationTool();
		findClasses.metadata = whiteboard;
		findClasses.visibility = AnnotationVisibility.unrestricted();
		findClasses.activate();
		listSources = new ListAnnotationSourcesTool();
		listSources.metadata = whiteboard;
		listSources.visibility = AnnotationVisibility.unrestricted();
		listSources.activate();
	}

	private String register(EPackage ePackage) {
		return whiteboard.registerPackage(ePackage).map(PackageMetadata::getModelFingerprint).orElseThrow();
	}

	@Test
	void theFixtureReallyProducesTwoCoexistingVersions() {
		assertThat(firstFingerprint)
				.as("diverging content under one nsURI must fingerprint differently, or nothing here is tested")
				.isNotEqualTo(secondFingerprint);
		assertThat(whiteboard.getPackageMetadataVersions(TestModels.UPLINK_NS_URI)).hasSize(2);
	}

	// ---- the refusal, and the discovery path out of it ----

	@Test
	void describePackageRefusesAnAmbiguousNsUriAndNamesTheFingerprints() {
		String error = callExpectingError(describePackage, Map.of("nsURI", TestModels.UPLINK_NS_URI));

		assertThat(error)
				.contains("holds 2 registered model versions")
				.contains("will not guess")
				.contains(firstFingerprint)
				.contains(secondFingerprint);
	}

	@Test
	void statusListsEveryVersionSoTheRefusalIsActionable() {
		Map<String, Object> result = call(status, Map.of());

		assertThat(strings(result, "ambiguousNamespaces")).containsExactly(TestModels.UPLINK_NS_URI);
		assertThat(String.valueOf(result.get("ambiguityNote"))).contains("pass the 'fingerprint'");

		@SuppressWarnings("unchecked")
		Map<String, List<Map<String, Object>>> byNamespace =
				(Map<String, List<Map<String, Object>>>) result.get("versionsByNamespace");
		assertThat(byNamespace.get(TestModels.UPLINK_NS_URI))
				.extracting(version -> version.get("modelFingerprint"))
				.containsExactlyInAnyOrder(firstFingerprint, secondFingerprint);
		assertThat(byNamespace.get(TestModels.GATEWAY_NS_URI)).hasSize(1);
	}

	@Test
	void describePackageAnswersExactlyWhenGivenAFingerprint() {
		Map<String, Object> result = call(describePackage, Map.of("fingerprint", secondFingerprint));

		assertThat(result)
				.containsEntry("modelFingerprint", secondFingerprint)
				.containsEntry("nsURI", TestModels.UPLINK_NS_URI)
				.containsEntry("versionCount", 2);
	}

	@Test
	void aFingerprintFromAnotherNamespaceIsAMismatchNotAPreference() {
		String error = callExpectingError(describePackage, Map.of(
				"nsURI", TestModels.GATEWAY_NS_URI,
				"fingerprint", firstFingerprint));

		assertThat(error).contains("identifies a model version of namespace").contains(TestModels.UPLINK_NS_URI);
	}

	@Test
	void anUnknownFingerprintSaysSoRatherThanFallingBackToTheNsUri() {
		String error = callExpectingError(describePackage, Map.of(
				"nsURI", TestModels.GATEWAY_NS_URI,
				"fingerprint", "fp1:nothing-registered-under-this"));

		assertThat(error).contains("No model version with fingerprint");
	}

	// ---- the same rule in the lookup tools ----

	@Test
	void findClassByNameRefusesAnAmbiguousNsUriAndAcceptsAFingerprint() {
		assertThat(callExpectingError(findClassByName, Map.of(
				"className", "SensorAUplink",
				"nsURI", TestModels.UPLINK_NS_URI)))
				.contains("holds 2 registered model versions");

		List<Map<String, Object>> classes = list(call(findClassByName, Map.of(
				"className", "SensorAUplink",
				"fingerprint", firstFingerprint)), "classes");
		assertThat(classes).hasSize(1);
		assertThat(classes.get(0)).containsEntry("modelFingerprint", firstFingerprint);
	}

	@Test
	void describeAspectsRefusesAnAmbiguousElementAndAcceptsAFingerprint() {
		assertThat(callExpectingError(describeAspects, Map.of("element", TestModels.SENSOR_A_UPLINK)))
				.contains("holds 2 registered model versions");

		Map<String, Object> result = call(describeAspects, Map.of(
				"element", TestModels.SENSOR_A_UPLINK,
				"fingerprint", secondFingerprint));
		assertThat(result)
				.containsEntry("kind", "class")
				.containsEntry("modelFingerprint", secondFingerprint);
	}

	@Test
	void listAnnotationSourcesRefusesAnAmbiguousNsUriAndAcceptsAFingerprint() {
		assertThat(callExpectingError(listSources, Map.of("nsURI", TestModels.UPLINK_NS_URI)))
				.contains("holds 2 registered model versions");

		assertThat(call(listSources, Map.of("fingerprint", firstFingerprint)))
				.containsEntry("scannedFingerprint", firstFingerprint)
				.containsEntry("scannedNsURI", TestModels.UPLINK_NS_URI);
	}

	// ---- wide queries stay wide, but stop hiding the second version ----

	@Test
	void anUnscopedAnnotationQueryReturnsBothVersionsRatherThanCollapsingThem() {
		List<Map<String, Object>> classes = list(call(findClasses, Map.of(
				"annotationSource", TestModels.TYPE_MAPPING_SOURCE,
				"key", TestModels.KEY_DISCRIMINATOR,
				"value", "Sensor_A")), "classes");

		// Same <nsURI>#//<Name> from two versions: de-duplicating on the reference alone
		// would drop one of these and say nothing about it.
		assertThat(classes).hasSize(2);
		assertThat(classes).allSatisfy(hit -> assertThat(hit).containsEntry("reference", TestModels.SENSOR_A_UPLINK));
		assertThat(classes).extracting(hit -> hit.get("modelFingerprint"))
				.containsExactlyInAnyOrder(firstFingerprint, secondFingerprint);
	}

	@Test
	void aFingerprintScopedAnnotationQueryReturnsOnlyThatVersion() {
		Map<String, Object> result = call(findClasses, Map.of(
				"annotationSource", TestModels.TYPE_MAPPING_SOURCE,
				"key", TestModels.KEY_DISCRIMINATOR,
				"value", "Sensor_A",
				"fingerprint", secondFingerprint));

		assertThat(ToolCalls.map(result, "query")).containsEntry("searchedAllVersions", Boolean.FALSE);
		List<Map<String, Object>> classes = list(result, "classes");
		assertThat(classes).hasSize(1);
		assertThat(classes.get(0)).containsEntry("modelFingerprint", secondFingerprint);
	}

	// ---- provenance ----

	@Test
	void aPackageRegisteredWithForeignContextIsNotAttributedToThisSession() {
		// What model.atlas's staged reads look like: registered with atlas.* build context
		// and no service.id. Reporting that as 'session' would claim the caller authored a
		// model it only read - exactly the provenance a compliance report must get right.
		EPackage external = TestModels.gatewayPackage();
		external.setNsURI("https://example.org/metadata/external");
		whiteboard.registerPackage(external, Map.of("atlas.remote", Boolean.TRUE, "atlas.stage", "draft"));

		Map<String, Object> described = call(describePackage,
				Map.of("nsURI", "https://example.org/metadata/external"));

		assertThat(described).containsEntry("origin", "external");
		// The registrant's own properties say where it came from; this bundle does not
		// need to know the vocabulary to pass them through.
		assertThat(ToolCalls.map(described, "properties")).containsEntry("atlas.stage", "draft");

		Map<String, Object> counts = ToolCalls.map(call(status, Map.of()), "packageVersionsByOrigin");
		assertThat(counts).containsEntry("external", 1).containsEntry("session", 3);
	}

	// ---- an unambiguous namespace is unaffected ----

	@Test
	void anNsUriWithOneVersionStillAnswersWithoutAFingerprint() {
		assertThat(call(describePackage, Map.of("nsURI", TestModels.GATEWAY_NS_URI)))
				.containsEntry("nsURI", TestModels.GATEWAY_NS_URI)
				.containsEntry("versionCount", 1);

		assertThat(list(call(findClassByName, Map.of(
				"className", "Gateway",
				"nsURI", TestModels.GATEWAY_NS_URI)), "classes")).hasSize(1);

		assertThat(call(describeAspects, Map.of("element", TestModels.GATEWAY)))
				.containsEntry("kind", "class");
	}
}
