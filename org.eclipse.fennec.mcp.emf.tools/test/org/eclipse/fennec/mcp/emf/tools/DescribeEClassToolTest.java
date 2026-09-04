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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.fennec.mcp.api.AnnotationVisibility;
import org.eclipse.fennec.mcp.emf.tools.core.ModelGuard;
import org.eclipse.fennec.mcp.emf.tools.core.TestModels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code describe_eclass} as the narrow metamodel read.
 * <p>
 * It used to report a class's shape but not its source: no EAnnotations, a
 * refusal on abstract classes, and supertypes as bare flattened names. That is
 * what pushed an agent to fetch a whole {@code .ecore} through
 * {@code export_package} in order to read the conventions of one or two classes.
 * These tests pin the three gaps closed, and pin the allow-list still holding
 * around them — permitting a read must not have widened what is readable.
 *
 * @author ilenia
 * @since Sep 3, 2026
 */
class DescribeEClassToolTest {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private EPackage library;
	private EPackage uplink;
	private EPackage.Registry packageRegistry;
	private McpAsyncServerExchange exchange;

	@BeforeEach
	void setUp() {
		library = TestModels.libraryPackage();
		uplink = TestModels.annotatedPackage(library);
		packageRegistry = TestModels.registryWith(library, uplink);
		exchange = mock(McpAsyncServerExchange.class);
		when(exchange.sessionId()).thenReturn("session-1");
	}

	@Test
	@DisplayName("an abstract class is described rather than refused")
	void abstractClassesAreDescribed() {
		Map<String, Object> described = describe(TestModels.UPLINK_BASE);

		// Refusing this was the instantiation guard leaking into a read: an abstract
		// base is the most useful class to read when copying a family's conventions.
		assertThat(described).containsEntry("name", "UplinkBase").containsEntry("abstract", true);
	}

	@Test
	@DisplayName("an interface is described too")
	void interfacesAreDescribed() {
		EClass base = (EClass) uplink.getEClassifier("UplinkBase");
		base.setInterface(true);

		assertThat(describe(TestModels.UPLINK_BASE)).containsEntry("abstract", true);
	}

	@Test
	@DisplayName("creating an instance of an abstract class is still refused")
	void abstractIsStillNotInstantiable() {
		ModelGuard guard = fullGuard();

		assertThat(guard.requireAllowedEClassForRead(TestModels.UPLINK_BASE)).isNotNull();
		// The read guard must not have become the instantiation guard.
		assertThatThrownBy(() -> guard.requireAllowedEClass(TestModels.UPLINK_BASE))
				.hasMessageContaining("abstract and cannot be instantiated");
	}

	@Test
	@DisplayName("a class outside the allow-list is still refused, abstract or not")
	void theAllowListStillHolds() {
		DescribeEClassTool tool = toolWith(guardFor(Set.of(TestModels.UPLINK_NS_URI), Set.of()));

		assertThat(callExpectingError(tool, Map.of("eClass", TestModels.UPLINK_BASE)))
				.as("permitting a read must not have widened what is readable")
				.contains("not allow-listed");
	}

	@Test
	@DisplayName("class annotations are reported with their exact source and keys")
	void classAnnotationsAreReported() {
		Map<String, Object> described = describe(TestModels.UPLINK_A);

		List<Map<String, Object>> annotations = annotationsOf(described);
		assertThat(annotations).hasSize(1);
		assertThat(annotations.get(0)).containsEntry("source", TestModels.TYPE_MAPPING_SOURCE);
		assertThat(detailsOf(annotations.get(0))).containsEntry("typeDiscriminator", "Sensor_A");
	}

	@Test
	@DisplayName("an ExtendedMetaData wire name on a feature is reported")
	void featureAnnotationsAreReported() {
		Map<String, Object> battery = feature(describe(TestModels.UPLINK_A), "batteryVoltage");

		List<Map<String, Object>> annotations = annotationsOf(battery);
		assertThat(annotations).hasSize(1);
		assertThat(annotations.get(0)).containsEntry("source", TestModels.EXTENDED_META_DATA_SOURCE);
		// The wire name the payload actually uses: invisible here before, which is
		// exactly the convention an agent is told to copy.
		assertThat(detailsOf(annotations.get(0))).containsEntry("name", "BatV");
	}

	@Test
	@DisplayName("a denied annotation source is withheld, and the count says so")
	void deniedSourcesAreWithheld() {
		DescribeEClassTool tool = toolWith(fullGuard(), denying(TestModels.TYPE_MAPPING_SOURCE));

		Map<String, Object> described = call(tool, Map.of("eClass", TestModels.UPLINK_A));

		assertThat(described).doesNotContainKey("annotations");
		// Counted, never named: naming the source would disclose exactly what the
		// deny-list withholds, while the count keeps the description honest about
		// being incomplete.
		assertThat(described).containsEntry("hiddenAnnotations", 1);
	}

	@Test
	@DisplayName("denying one source leaves the others in place")
	void otherSourcesSurviveTheDenial() {
		DescribeEClassTool tool = toolWith(fullGuard(), denying(TestModels.TYPE_MAPPING_SOURCE));

		Map<String, Object> battery = feature(call(tool, Map.of("eClass", TestModels.UPLINK_A)),
				"batteryVoltage");

		assertThat(annotationsOf(battery)).hasSize(1);
		assertThat(annotationsOf(battery).get(0)).containsEntry("source", TestModels.EXTENDED_META_DATA_SOURCE);
		assertThat(battery).doesNotContainKey("hiddenAnnotations");
	}

	@Test
	@DisplayName("a prefix rule withholds a whole vocabulary")
	void prefixRulesWithholdAVocabulary() {
		DescribeEClassTool tool = toolWith(fullGuard(), denying("http://eclipse.org/fennec/codec/*"));

		assertThat(call(tool, Map.of("eClass", TestModels.UPLINK_A))).containsEntry("hiddenAnnotations", 1);
	}

	@Test
	@DisplayName("nothing is withheld when no source is denied")
	void noDenialMeansNoCount() {
		assertThat(describe(TestModels.UPLINK_A)).doesNotContainKey("hiddenAnnotations");
	}

	@Test
	@DisplayName("a class with no annotations omits the field instead of reporting an empty list")
	void unannotatedClassesStaySmall() {
		assertThat(describe(TestModels.BOOK)).doesNotContainKey("annotations");
	}

	@Test
	@DisplayName("declared supertypes are qualified and kept apart from the inherited ones")
	void superTypesAreQualifiedAndSplit() {
		Map<String, Object> described = describe(TestModels.UPLINK_A);

		// A bare 'AbstractItem' could not be fed back into any tool, and did not say
		// that this supertype lives in another package at all.
		assertThat(refs(described, "superTypes")).containsExactly(TestModels.UPLINK_BASE,
				TestModels.ABSTRACT_ITEM);
		assertThat(refs(described, "allSuperTypes")).containsExactlyInAnyOrder(TestModels.UPLINK_BASE,
				TestModels.ABSTRACT_ITEM);
	}

	@Test
	@DisplayName("a class with no supertypes omits both fields")
	void noSuperTypesMeansNoFields() {
		assertThat(describe(TestModels.BOOK)).doesNotContainKeys("superTypes", "allSuperTypes");
	}

	@Test
	@DisplayName("allSuperTypes goes deeper than superTypes in a three-level hierarchy")
	void transitiveClosureIsDeeper() {
		EClass middle = (EClass) uplink.getEClassifier("UplinkA");
		EClass leaf = EcoreFactory.eINSTANCE.createEClass();
		leaf.setName("UplinkALeaf");
		leaf.getESuperTypes().add(middle);
		uplink.getEClassifiers().add(leaf);

		Map<String, Object> described = describe(TestModels.UPLINK_NS_URI + "#//UplinkALeaf");

		assertThat(refs(described, "superTypes")).containsExactly(TestModels.UPLINK_A);
		assertThat(refs(described, "allSuperTypes")).containsExactlyInAnyOrder(TestModels.UPLINK_A,
				TestModels.UPLINK_BASE, TestModels.ABSTRACT_ITEM);
	}

	@Test
	@DisplayName("an inherited feature says so and names where it was declared")
	void inheritedFeaturesAreMarked() {
		EClass base = (EClass) uplink.getEClassifier("UplinkBase");
		EClass uplinkA = (EClass) uplink.getEClassifier("UplinkA");
		// move the attribute up: same flattened feature list, different origin
		base.getEStructuralFeatures().addAll(uplinkA.getEStructuralFeatures());

		Map<String, Object> battery = feature(describe(TestModels.UPLINK_A), "batteryVoltage");

		// Without this an agent re-declares the feature on its own subclass.
		assertThat(battery).containsEntry("inherited", true).containsEntry("declaringClass",
				TestModels.UPLINK_BASE);
	}

	@Test
	@DisplayName("a locally declared feature carries neither marker")
	void localFeaturesAreNotMarked() {
		Map<String, Object> title = feature(describe(TestModels.BOOK), "title");

		assertThat(title).doesNotContainKeys("inherited", "declaringClass");
	}

	@Test
	@DisplayName("the existing shape is untouched: kind, type, multiplicity, enum literals")
	void theEstablishedFieldsStillHold() {
		Map<String, Object> described = describe(TestModels.BOOK);

		assertThat(described).containsEntry("eClass", TestModels.BOOK).containsEntry("package",
				TestModels.NS_URI);
		assertThat(feature(described, "tags")).containsEntry("kind", "attribute").containsEntry("many", true);
		assertThat(feature(described, "author")).containsEntry("kind", "reference").containsEntry("type",
				TestModels.WRITER);
		assertThat(refs(feature(described, "genre"), "enumLiterals")).containsExactly("FANTASY", "SCIFI");
	}

	private Map<String, Object> describe(String eClassRef) {
		return call(toolWith(fullGuard()), Map.of("eClass", eClassRef));
	}

	private DescribeEClassTool toolWith(ModelGuard guard) {
		return toolWith(guard, AnnotationVisibility.unrestricted());
	}

	private DescribeEClassTool toolWith(ModelGuard guard, AnnotationVisibility visibility) {
		DescribeEClassTool tool = new DescribeEClassTool();
		tool.guard = guard;
		tool.visibility = visibility;
		tool.activate();
		return tool;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> annotationsOf(Map<String, Object> described) {
		return (List<Map<String, Object>>) (Object) described.get("annotations");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> detailsOf(Map<String, Object> annotation) {
		return (Map<String, Object>) annotation.get("details");
	}

	@SuppressWarnings("unchecked")
	private static List<String> refs(Map<String, Object> owner, String key) {
		return (List<String>) (Object) owner.get(key);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> feature(Map<String, Object> described, String name) {
		List<Map<String, Object>> features = (List<Map<String, Object>>) (Object) described.get("features");
		return features.stream().filter(f -> name.equals(f.get("name"))).findFirst().orElseThrow();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> call(AbstractEMFTool tool, Map<String, Object> arguments) {
		McpSchema.CallToolResult result = tool.execute(exchange, arguments).block();
		assertThat(result).isNotNull();
		String text = ((McpSchema.TextContent) result.content().get(0)).text();
		assertThat(result.isError()).as("tool error: %s", text).isNotEqualTo(Boolean.TRUE);
		return MAPPER.readValue(text, Map.class);
	}

	private String callExpectingError(AbstractEMFTool tool, Map<String, Object> arguments) {
		McpSchema.CallToolResult result = tool.execute(exchange, arguments).block();
		assertThat(result).isNotNull();
		assertThat(result.isError()).isEqualTo(Boolean.TRUE);
		return ((McpSchema.TextContent) result.content().get(0)).text();
	}

	/**
	 * A visibility policy denying exactly the given source patterns, built through
	 * the real component so the test exercises the deployed matching rather than a
	 * stub's idea of it.
	 */
	private static AnnotationVisibility denying(String... sourcePatterns) {
		return AnnotationVisibility.denying(List.of(sourcePatterns), List.of());
	}

	private ModelGuard fullGuard() {
		Set<String> classes = Stream.of(library, uplink)
				.flatMap(p -> p.getEClassifiers().stream())
				.filter(EClass.class::isInstance)
				.map(EClass.class::cast)
				.map(ModelGuard::refOf)
				.collect(Collectors.toSet());
		return guardFor(Set.of(TestModels.NS_URI, TestModels.UPLINK_NS_URI), classes);
	}

	private ModelGuard guardFor(Set<String> packages, Set<String> classes) {
		try {
			var constructor = ModelGuard.class.getDeclaredConstructor(EPackage.Registry.class, Set.class, Set.class);
			constructor.setAccessible(true);
			return constructor.newInstance(packageRegistry, packages, classes);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}
}
