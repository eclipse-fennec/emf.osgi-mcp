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
package org.eclipse.fennec.mcp.emf.tools.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the JSON coverage analysis behind {@code create_from_json}'s
 * report.
 * <p>
 * The analysis is fed hand-built object graphs rather than codec output, which
 * is the point of it being a separate class: a "the codec dropped this" claim
 * has to be provably correct, and a test that goes through the codec could only
 * assert that two pieces of the same machinery agree. Here the graph is stated
 * explicitly, so what the payload said and what the model holds are independent.
 *
 * @author ilenia
 * @since Sep 3, 2026
 */
class JsonCoverageTest {

	private EPackage library;
	private EPackage uplink;

	@BeforeEach
	void setUp() {
		library = TestModels.libraryPackage();
		uplink = TestModels.annotatedPackage(library);
	}

	@Test
	@DisplayName("a payload whose every key landed reports complete")
	void completePayload() {
		EObject libraryObject = create(library, "Library");
		set(libraryObject, "name", "City Library");

		JsonLoadReport report = JsonCoverage.analyse(libraryObject, payload("name", "City Library"));

		assertThat(report.isComplete()).isTrue();
		assertThat(report.matchedKeys()).isEqualTo(1);
		assertThat(report.unmatchedPaths()).isEmpty();
		assertThat(report.droppedPaths()).isEmpty();
	}

	@Test
	@DisplayName("a key matching no feature is reported with its path")
	void unknownLeafKey() {
		EObject libraryObject = create(library, "Library");
		set(libraryObject, "name", "City Library");

		JsonLoadReport report = JsonCoverage.analyse(libraryObject,
				payload("name", "City Library", "municipality", "Jena"));

		assertThat(report.unmatchedPaths()).containsExactly("$.municipality");
		assertThat(report.matchedKeys()).isEqualTo(1);
		assertThat(report.isComplete()).isFalse();
	}

	@Test
	@DisplayName("a key unknown inside a nested containment carries the nested path")
	void unknownNestedKey() {
		EObject book = create(library, "Book");
		set(book, "title", "Dune");
		EObject libraryObject = create(library, "Library");
		set(libraryObject, "name", "City Library");
		add(libraryObject, "books", book);

		Map<String, Object> data = payload("name", "City Library");
		data.put("books", List.of(payload("title", "Dune", "isbn", "978-0441013593")));

		JsonLoadReport report = JsonCoverage.analyse(libraryObject, data);

		assertThat(report.unmatchedPaths()).containsExactly("$.books[0].isbn");
		// name, books, title
		assertThat(report.matchedKeys()).isEqualTo(3);
	}

	@Test
	@DisplayName("a key using an ExtendedMetaData wire name counts as matched")
	void extendedMetaDataWireName() {
		EObject uplinkObject = create(uplink, "UplinkA");
		set(uplinkObject, "batteryVoltage", 3.7d);

		JsonLoadReport report = JsonCoverage.analyse(uplinkObject, payload("BatV", 3.7d));

		assertThat(report.unmatchedPaths()).isEmpty();
		assertThat(report.matchedKeys()).isEqualTo(1);
		assertThat(report.unsetFeatures()).isEmpty();
	}

	@Test
	@DisplayName("the plain name of a wire-named feature is accepted too, rather than claimed unmatched")
	void plainNameOfWireNamedFeature() {
		EObject uplinkObject = create(uplink, "UplinkA");
		set(uplinkObject, "batteryVoltage", 3.7d);

		JsonLoadReport report = JsonCoverage.analyse(uplinkObject, payload("batteryVoltage", 3.7d));

		// Deliberate under-reporting: the codec may only accept 'BatV', but claiming
		// a key is unmatched when a feature of that name exists would be the one
		// error an agent cannot recover from.
		assertThat(report.unmatchedPaths()).isEmpty();
	}

	@Test
	@DisplayName("resolveFeature prefers the plain name and falls back to the wire name")
	void featureResolutionOrder() {
		EClass uplinkA = (EClass) uplink.getEClassifier("UplinkA");

		assertThat(JsonCoverage.resolveFeature(uplinkA, "batteryVoltage")).isNotNull();
		assertThat(JsonCoverage.resolveFeature(uplinkA, "BatV"))
				.isSameAs(JsonCoverage.resolveFeature(uplinkA, "batteryVoltage"));
		assertThat(JsonCoverage.resolveFeature(uplinkA, "voltage")).isNull();
	}

	@Test
	@DisplayName("a key whose feature is empty afterwards is reported as dropped")
	void valueDidNotLand() {
		// 'name' is an EString with no default: an unset one reads back as null,
		// which is what makes this case detectable at all.
		EObject libraryObject = create(library, "Library");

		JsonLoadReport report = JsonCoverage.analyse(libraryObject, payload("name", "City Library"));

		assertThat(report.matchedKeys()).isEqualTo(1);
		assertThat(report.droppedPaths()).containsExactly("$.name");
		assertThat(report.isComplete()).isFalse();
	}

	@Test
	@DisplayName("an attribute that fell back to its type default is deliberately not reported")
	void defaultValuedAttributeIsNotFlagged() {
		// 'pages' is an EInt: unset reads back as 0, indistinguishable from a
		// deliberate 0 without comparing converted values. Pinned so the
		// under-reporting stays a decision rather than becoming a regression.
		EObject book = create(library, "Book");
		set(book, "title", "Dune");

		JsonLoadReport report = JsonCoverage.analyse(book, payload("title", "Dune", "pages", 412));

		assertThat(report.droppedPaths()).isEmpty();
		assertThat(report.isComplete()).isTrue();
	}

	@Test
	@DisplayName("a containment entry the graph does not have is reported by index")
	void containmentListShorterThanPayload() {
		EObject book = create(library, "Book");
		set(book, "title", "Dune");
		EObject libraryObject = create(library, "Library");
		set(libraryObject, "name", "City Library");
		add(libraryObject, "books", book);

		Map<String, Object> data = payload("name", "City Library");
		data.put("books", List.of(payload("title", "Dune"), payload("title", "Emma")));

		JsonLoadReport report = JsonCoverage.analyse(libraryObject, data);

		assertThat(report.droppedPaths()).containsExactly("$.books[1]");
		assertThat(report.unmatchedPaths()).isEmpty();
	}

	@Test
	@DisplayName("a nested object the graph does not have at all is reported as dropped")
	void missingNestedObject() {
		EObject book = create(library, "Book");
		set(book, "title", "Dune");
		Map<String, Object> data = payload("title", "Dune");
		data.put("author", payload("name", "Frank Herbert"));

		// 'author' is a cross-reference, not containment: an unset one is null and
		// the payload offered an object for it.
		JsonLoadReport report = JsonCoverage.analyse(book, data);

		assertThat(report.droppedPaths()).containsExactly("$.author");
	}

	@Test
	@DisplayName("features no key mentioned are listed as a hint, per visited class")
	void unsetFeaturesAreListed() {
		EObject book = create(library, "Book");
		set(book, "title", "Dune");
		EObject libraryObject = create(library, "Library");
		set(libraryObject, "name", "City Library");
		add(libraryObject, "books", book);

		Map<String, Object> data = payload("name", "City Library");
		data.put("books", List.of(payload("title", "Dune")));

		JsonLoadReport report = JsonCoverage.analyse(libraryObject, data);

		assertThat(report.unsetFeatures())
				.containsExactlyInAnyOrder("Library.writers", "Library.featuredBook", "Book.pages", "Book.genre",
						"Book.tags", "Book.author")
				// mentioned by the payload, so not a gap — 'books' at the root, 'title'
				// inside it, which is what makes this per-visited-class rather than
				// per-root
				.doesNotContain("Library.name", "Library.books", "Book.title");
	}

	@Test
	@DisplayName("codec diagnostics are carried into the report and defeat completeness")
	void codecDiagnosticsCarried() {
		EObject libraryObject = create(library, "Library");
		set(libraryObject, "name", "City Library");

		JsonLoadReport report = JsonCoverage.analyse(libraryObject, payload("name", "City Library"),
				List.of("could not resolve type for 'municipality'"));

		assertThat(report.codecDiagnostics()).containsExactly("could not resolve type for 'municipality'");
		assertThat(report.isComplete()).isFalse();
		assertThat(report.toMap()).containsEntry("codecDiagnostics",
				List.of("could not resolve type for 'municipality'"));
	}

	@Test
	@DisplayName("a clean report stays small: empty lists are omitted from the map")
	void cleanReportMapIsSmall() {
		EObject writer = create(library, "Writer");
		set(writer, "name", "Frank Herbert");

		Map<String, Object> map = JsonCoverage.analyse(writer, payload("name", "Frank Herbert")).toMap();

		assertThat(map).containsEntry("complete", true).containsEntry("matchedKeys", 1)
				.containsEntry("unmatchedCount", 0);
		assertThat(map).doesNotContainKeys("unmatchedPaths", "droppedPaths", "droppedCount", "unsetFeatures",
				"codecDiagnostics", "truncated");
	}

	@Test
	@DisplayName("long lists are truncated in the map while the count stays exact")
	void reportedListsAreCapped() {
		int unknownKeys = JsonLoadReport.MAX_REPORTED + 10;
		EObject writer = create(library, "Writer");
		set(writer, "name", "Frank Herbert");
		Map<String, Object> data = payload("name", "Frank Herbert");
		for (int index = 0; index < unknownKeys; index++) {
			data.put("unknown" + index, index);
		}

		JsonLoadReport report = JsonCoverage.analyse(writer, data);
		Map<String, Object> map = report.toMap();

		assertThat(report.unmatchedPaths()).hasSize(unknownKeys);
		assertThat(map).containsEntry("unmatchedCount", unknownKeys).containsEntry("truncated", Boolean.TRUE);
		assertThat((List<?>) map.get("unmatchedPaths")).hasSize(JsonLoadReport.MAX_REPORTED);
	}

	@Test
	@DisplayName("describeUnmatched names the paths and the totals")
	void describeUnmatchedSummary() {
		EObject writer = create(library, "Writer");
		set(writer, "name", "Frank Herbert");

		JsonLoadReport report = JsonCoverage.analyse(writer, payload("name", "Frank Herbert", "born", 1920));

		assertThat(report.describeUnmatched()).isEqualTo("1 of 2 payload keys matched no feature: $.born");
	}

	private static EObject create(EPackage ePackage, String className) {
		EClass eClass = (EClass) ePackage.getEClassifier(className);
		assertThat(eClass).as("EClass '%s'", className).isNotNull();
		return ePackage.getEFactoryInstance().create(eClass);
	}

	private static void set(EObject eObject, String featureName, Object value) {
		eObject.eSet(feature(eObject, featureName), value);
	}

	@SuppressWarnings("unchecked")
	private static void add(EObject eObject, String featureName, EObject value) {
		((List<EObject>) eObject.eGet(feature(eObject, featureName))).add(value);
	}

	private static EStructuralFeature feature(EObject eObject, String featureName) {
		EStructuralFeature feature = eObject.eClass().getEStructuralFeature(featureName);
		assertThat(feature).as("feature '%s' of %s", featureName, eObject.eClass().getName()).isNotNull();
		return feature;
	}

	/**
	 * A payload in document order. {@code Map.of} would not do: the report lists
	 * paths in traversal order, and an unordered map makes the assertions depend
	 * on hash iteration.
	 */
	private static Map<String, Object> payload(Object... keysAndValues) {
		Map<String, Object> data = new LinkedHashMap<>();
		List<Object> entries = new ArrayList<>(List.of(keysAndValues));
		for (int index = 0; index < entries.size(); index += 2) {
			data.put((String) entries.get(index), entries.get(index + 1));
		}
		return data;
	}
}
