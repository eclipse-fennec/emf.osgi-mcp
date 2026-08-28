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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.fennec.emf.osgi.ResourceSetFactory;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetLimits;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.ModelGuard;
import org.eclipse.fennec.mcp.emf.tools.core.PackageRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

/**
 * Composite authoring: {@code create_epackage} with nested {@code eClassifiers}
 * and {@code add_eclass} with nested features, which collapse the
 * one-call-per-element chain an agent otherwise pays an iteration for (see
 * issue #32).
 *
 * @author Mark Hoffmann
 * @since Aug 28, 2026
 */
class CompositeAuthoringTest {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final String NS_URI = "http://example.org/composite";

	private McpAsyncServerExchange exchange;
	private CreateDatasetTool createDataset;
	private CreateEPackageTool createEPackage;
	private AddEClassTool addEClass;
	private AddEAttributeTool addEAttribute;
	private AddEReferenceTool addEReference;
	private AddEEnumTool addEEnum;
	private AddEEnumLiteralTool addEEnumLiteral;
	private AddEAnnotationTool addEAnnotation;
	private ExportDatasetTool exportDataset;
	private InspectDatasetTool inspectDataset;
	private RegisterPackageTool registerPackage;

	@BeforeEach
	void setUp() throws Exception {
		ResourceSetFactory factory = ResourceSetImpl::new;
		PackageRegistry packages = newPackageRegistry(Set.of("*"), Set.of(), 100);
		ModelGuard guard = newGuard(packages);
		DatasetRegistry registry = newDatasetRegistry(factory);
		exchange = mock(McpAsyncServerExchange.class);
		when(exchange.sessionId()).thenReturn("session-1");

		createDataset = new CreateDatasetTool();
		createDataset.registry = registry;
		createDataset.activate();
		createEPackage = new CreateEPackageTool();
		createEPackage.registry = registry;
		createEPackage.guard = guard;
		createEPackage.activate();
		addEClass = new AddEClassTool();
		addEClass.registry = registry;
		addEClass.guard = guard;
		addEClass.activate();
		addEAttribute = new AddEAttributeTool();
		addEAttribute.registry = registry;
		addEAttribute.guard = guard;
		addEAttribute.activate();
		addEReference = new AddEReferenceTool();
		addEReference.registry = registry;
		addEReference.guard = guard;
		addEReference.activate();
		addEEnum = new AddEEnumTool();
		addEEnum.registry = registry;
		addEEnum.activate();
		addEEnumLiteral = new AddEEnumLiteralTool();
		addEEnumLiteral.registry = registry;
		addEEnumLiteral.activate();
		addEAnnotation = new AddEAnnotationTool();
		addEAnnotation.registry = registry;
		addEAnnotation.activate();
		exportDataset = new ExportDatasetTool();
		exportDataset.registry = registry;
		exportDataset.activate();
		inspectDataset = new InspectDatasetTool();
		inspectDataset.registry = registry;
		inspectDataset.activate();
		registerPackage = new RegisterPackageTool();
		registerPackage.registry = registry;
		registerPackage.packages = packages;
		registerPackage.activate();
	}

	/**
	 * The acceptance criterion of issue #32: one composite call must produce the
	 * same metamodel as the standalone sequence it replaces. Compared through
	 * the exported .ecore, which is the artefact both paths exist to produce.
	 */
	@Test
	void compositeCallProducesTheSameEcoreAsTheStandaloneSequence() {
		String stepwise = (String) call(createDataset, Map.of()).get("datasetId");
		String stepwisePkg = (String) call(createEPackage, Map.of("datasetId", stepwise,
				"name", "composite", "nsURI", NS_URI, "nsPrefix", "comp")).get("objectId");
		String decoded = (String) call(addEClass, Map.of("datasetId", stepwise, "packageObjectId", stepwisePkg,
				"name", "DecodedObject")).get("objectId");
		call(addEAttribute, Map.of("datasetId", stepwise, "classObjectId", decoded, "name", "key", "eType", ecore("EString")));
		String uplink = (String) call(addEClass, Map.of("datasetId", stepwise, "packageObjectId", stepwisePkg,
				"name", "Uplink")).get("objectId");
		call(addEAnnotation, Map.of("datasetId", stepwise, "targetObjectId", uplink,
				"source", "http://example.org/discriminator", "details", Map.of("port", "85")));
		call(addEAttribute, Map.of("datasetId", stepwise, "classObjectId", uplink, "name", "batV", "eType", ecore("EDouble")));
		call(addEReference, Map.of("datasetId", stepwise, "classObjectId", uplink, "name", "object",
				"eType", decoded, "containment", true, "upperBound", -1));

		String composite = (String) call(createDataset, Map.of()).get("datasetId");
		call(createEPackage, Map.of("datasetId", composite, "name", "composite", "nsURI", NS_URI, "nsPrefix", "comp",
				"eClassifiers", List.of(
						Map.of("name", "DecodedObject",
								"eAttributes", List.of(Map.of("name", "key", "eType", ecore("EString")))),
						Map.of("name", "Uplink",
								"eAnnotations", List.of(Map.of("source", "http://example.org/discriminator",
										"details", Map.of("port", "85"))),
								"eAttributes", List.of(Map.of("name", "batV", "eType", ecore("EDouble"))),
								"eReferences", List.of(Map.of("name", "object", "eType", "#//DecodedObject",
										"containment", true, "upperBound", -1))))));

		assertThat(ecoreOf(composite)).isEqualTo(ecoreOf(stepwise));
		assertThat(objectCount(composite)).isEqualTo(objectCount(stepwise));
	}

	/**
	 * Declaration order must not matter: Uplink references DecodedObject before
	 * DecodedObject is declared. This is what the second wiring pass buys.
	 */
	@Test
	void classifiersMayReferenceOnesDeclaredLater() {
		String datasetId = (String) call(createDataset, Map.of()).get("datasetId");
		call(createEPackage, Map.of("datasetId", datasetId, "name", "composite", "nsURI", NS_URI, "nsPrefix", "comp",
				"eClassifiers", List.of(
						Map.of("name", "Uplink",
								"eSuperTypes", List.of("#//AbstractMessage"),
								"eReferences", List.of(Map.of("name", "object", "eType", "#//DecodedObject",
										"containment", true))),
						Map.of("name", "AbstractMessage", "abstract", true),
						Map.of("name", "DecodedObject"))));

		// the export writes an intra-document reference without the '#'
		String ecore = ecoreOf(datasetId);
		assertThat(ecore).contains("eSuperTypes=\"//AbstractMessage\"").contains("eType=\"//DecodedObject\"");
	}

	/**
	 * A nested EEnum carries its literals, so a closed value set is one call
	 * rather than one call per literal.
	 */
	@Test
	void nestedEnumCarriesItsLiterals() {
		String datasetId = (String) call(createDataset, Map.of()).get("datasetId");
		call(createEPackage, Map.of("datasetId", datasetId, "name", "composite", "nsURI", NS_URI, "nsPrefix", "comp",
				"eClassifiers", List.of(
						Map.of("eClass", "EEnum", "name", "Quality", "eLiterals", List.of(
								Map.of("name", "GOOD", "value", 0),
								Map.of("name", "BAD", "value", 1))),
						Map.of("name", "Reading",
								"eAttributes", List.of(Map.of("name", "quality", "eType", "#//Quality"))))));

		String ecore = ecoreOf(datasetId);
		assertThat(ecore).contains("GOOD").contains("BAD").contains("eType=\"//Quality\"");
	}

	/**
	 * The nested elements come back with their objectIds, so the agent can go on
	 * addressing them without spending an iteration on inspect_dataset.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void compositeCallReportsTheObjectIdOfEveryNestedElement() {
		String datasetId = (String) call(createDataset, Map.of()).get("datasetId");
		Map<String, Object> result = call(createEPackage, Map.of("datasetId", datasetId, "name", "composite",
				"nsURI", NS_URI, "nsPrefix", "comp",
				"eClassifiers", List.of(Map.of("name", "Uplink",
						"eAttributes", List.of(Map.of("name", "batV", "eType", ecore("EDouble")))))));

		List<Map<String, String>> created = (List<Map<String, String>>) result.get("created");
		assertThat(created).extracting(element -> element.get("type") + ":" + element.get("name"))
				.containsExactly("EPackage:composite", "EClass:Uplink", "EAttribute:batV");
		assertThat(created.get(0).get("objectId")).isEqualTo(result.get("objectId"));
		// every reported id addresses a real object
		String batVId = created.get(2).get("objectId");
		Map<String, Object> uplink = call(addEClass, Map.of("datasetId", datasetId,
				"packageObjectId", result.get("objectId"), "name", "Other",
				"eReferences", List.of(Map.of("name", "keyed", "eType", "#//Uplink", "eKeys", List.of(batVId)))));
		assertThat(uplink.get("objectId")).isNotNull();
	}

	/**
	 * A failure inside a nested array must name the element that failed, by
	 * array index and by name.
	 */
	@Test
	void nestedFailureNamesTheOffendingElement() {
		String datasetId = (String) call(createDataset, Map.of()).get("datasetId");
		String message = callExpectingError(createEPackage, Map.of("datasetId", datasetId, "name", "composite",
				"nsURI", NS_URI, "nsPrefix", "comp",
				"eClassifiers", List.of(
						Map.of("name", "Fine", "eAttributes", List.of(Map.of("name", "ok", "eType", ecore("EString")))),
						Map.of("name", "Broken", "eAttributes", List.of(
								Map.of("name", "good", "eType", ecore("EString")),
								Map.of("name", "bad", "eType", "#//NoSuchType"))))));

		assertThat(message).contains("eClassifiers[1] 'Broken'").contains("eAttributes[1] 'bad'");
	}

	/**
	 * A composite call is all-or-nothing against the dataset: a failure part-way
	 * registers nothing, so the dataset stays usable and the agent can simply
	 * retry with a corrected payload.
	 */
	@Test
	void failedCompositeCallLeavesTheDatasetUntouched() {
		String datasetId = (String) call(createDataset, Map.of()).get("datasetId");
		int before = objectCount(datasetId);

		callExpectingError(createEPackage, Map.of("datasetId", datasetId, "name", "composite",
				"nsURI", NS_URI, "nsPrefix", "comp",
				"eClassifiers", List.of(Map.of("name", "Broken",
						"eAttributes", List.of(Map.of("name", "bad", "eType", "#//NoSuchType"))))));

		assertThat(objectCount(datasetId)).as("nothing registered by the failed call").isEqualTo(before);

		// the dataset is still usable: the corrected payload goes straight through
		Map<String, Object> retry = call(createEPackage, Map.of("datasetId", datasetId, "name", "composite",
				"nsURI", NS_URI, "nsPrefix", "comp",
				"eClassifiers", List.of(Map.of("name", "Fixed",
						"eAttributes", List.of(Map.of("name", "good", "eType", ecore("EString")))))));
		Map<String, Object> registered = call(registerPackage,
				Map.of("datasetId", datasetId, "packageObjectId", retry.get("objectId")));
		assertThat(registered.get("valid")).isEqualTo(Boolean.TRUE);
	}

	/**
	 * The same all-or-nothing rule for a composite add_eclass: the half-built
	 * class must not end up in the package either.
	 */
	@Test
	void failedCompositeAddEClassLeavesThePackageUntouched() {
		String datasetId = (String) call(createDataset, Map.of()).get("datasetId");
		String pkgId = (String) call(createEPackage, Map.of("datasetId", datasetId, "name", "composite",
				"nsURI", NS_URI, "nsPrefix", "comp")).get("objectId");
		int before = objectCount(datasetId);

		callExpectingError(addEClass, Map.of("datasetId", datasetId, "packageObjectId", pkgId, "name", "Broken",
				"eAttributes", List.of(Map.of("name", "bad", "eType", "#//NoSuchType"))));

		assertThat(objectCount(datasetId)).isEqualTo(before);
		assertThat(ecoreOf(datasetId)).doesNotContain("Broken");
	}

	/**
	 * Backward compatibility: omitting the new arrays leaves both tools behaving
	 * exactly as before, down to the returned shape.
	 */
	@Test
	void omittingTheNestedArraysKeepsTheOriginalBehaviour() {
		String datasetId = (String) call(createDataset, Map.of()).get("datasetId");
		Map<String, Object> pkg = call(createEPackage, Map.of("datasetId", datasetId, "name", "composite",
				"nsURI", NS_URI, "nsPrefix", "comp"));
		assertThat(pkg).containsEntry("nsURI", NS_URI);
		assertThat(((Number) pkg.get("objectCount")).intValue()).isEqualTo(1);

		Map<String, Object> eClass = call(addEClass, Map.of("datasetId", datasetId,
				"packageObjectId", pkg.get("objectId"), "name", "Plain"));
		assertThat(eClass).containsEntry("eClass", NS_URI + "#//Plain");
		assertThat(((Number) eClass.get("objectCount")).intValue()).isEqualTo(2);

		// and the standalone feature tools still add to it
		call(addEAttribute, Map.of("datasetId", datasetId, "classObjectId", eClass.get("objectId"),
				"name", "label", "eType", ecore("EString")));
		String enumId = (String) call(addEEnum, Map.of("datasetId", datasetId,
				"packageObjectId", pkg.get("objectId"), "name", "Flag")).get("objectId");
		call(addEEnumLiteral, Map.of("datasetId", datasetId, "eenumObjectId", enumId, "name", "ON", "value", 1));
		assertThat(ecoreOf(datasetId)).contains("Plain").contains("label").contains("ON");
	}

	/**
	 * An unknown {@code eClass} discriminator is rejected with the supported
	 * values rather than silently creating an EClass.
	 */
	@Test
	void unknownClassifierKindIsRejected() {
		String datasetId = (String) call(createDataset, Map.of()).get("datasetId");
		String message = callExpectingError(createEPackage, Map.of("datasetId", datasetId, "name", "composite",
				"nsURI", NS_URI, "nsPrefix", "comp",
				"eClassifiers", List.of(Map.of("eClass", "EIntefrace", "name", "Oops"))));
		assertThat(message).contains("eClassifiers[0] 'Oops'").contains("EClass, EEnum, EDataType");
	}

	private String ecoreOf(String datasetId) {
		return (String) call(exportDataset, Map.of("datasetId", datasetId, "validate", false)).get("content");
	}

	private int objectCount(String datasetId) {
		return ((Number) call(inspectDataset, Map.of("datasetId", datasetId)).get("objectCount")).intValue();
	}

	private static String ecore(String name) {
		return EcorePackage.eNS_URI + "#//" + name;
	}

	private String callExpectingError(AbstractEMFTool tool, Map<String, Object> arguments) {
		McpSchema.CallToolResult result = tool.execute(exchange, arguments).block();
		assertThat(result).isNotNull();
		String text = ((McpSchema.TextContent) result.content().get(0)).text();
		assertThat(result.isError()).as("expected an error, got: %s", text).isEqualTo(Boolean.TRUE);
		return text;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> call(AbstractEMFTool tool, Map<String, Object> arguments) {
		McpSchema.CallToolResult result = tool.execute(exchange, arguments).block();
		assertThat(result).isNotNull();
		String text = ((McpSchema.TextContent) result.content().get(0)).text();
		assertThat(result.isError()).as("tool error: %s", text).isNotEqualTo(Boolean.TRUE);
		return MAPPER.readValue(text, Map.class);
	}

	private static PackageRegistry newPackageRegistry(Set<String> allow, Set<String> deny, int maxModels) throws Exception {
		var ctor = PackageRegistry.class.getDeclaredConstructor(Set.class, Set.class, int.class);
		ctor.setAccessible(true);
		return ctor.newInstance(allow, deny, maxModels);
	}

	private static ModelGuard newGuard(PackageRegistry packages) throws Exception {
		var ctor = ModelGuard.class.getDeclaredConstructor(EPackage.Registry.class, PackageRegistry.class, Set.class, Set.class);
		ctor.setAccessible(true);
		return ctor.newInstance(new org.eclipse.emf.ecore.impl.EPackageRegistryImpl(), packages, Set.of(), Set.of());
	}

	private static DatasetRegistry newDatasetRegistry(ResourceSetFactory factory) throws Exception {
		var ctor = DatasetRegistry.class.getDeclaredConstructor(ResourceSetFactory.class, DatasetLimits.class);
		ctor.setAccessible(true);
		return ctor.newInstance(factory, DatasetLimits.defaults());
	}
}
