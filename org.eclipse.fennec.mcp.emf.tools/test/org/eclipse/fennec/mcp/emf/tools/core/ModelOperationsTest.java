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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.impl.EPackageRegistryImpl;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests instance creation, coercion, feature modification, deletion and
 * recipe replay of {@link ModelOperations}.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
class ModelOperationsTest {

	private EPackage libraryPackage;
	private ModelGuard guard;
	private Dataset dataset;
	private DatasetLimits limits;
	private EClass libraryClass;
	private EClass bookClass;
	private EClass writerClass;

	@BeforeEach
	void setUp() {
		libraryPackage = TestModels.libraryPackage();
		EPackage.Registry registry = TestModels.registryWith(libraryPackage);
		guard = new ModelGuard(registry, Set.of(TestModels.NS_URI), Set.of(TestModels.LIBRARY, TestModels.BOOK, TestModels.WRITER));
		limits = DatasetLimits.defaults();
		dataset = new Dataset("test-ds", resourceSet(registry), null);
		libraryClass = (EClass) libraryPackage.getEClassifier("Library");
		bookClass = (EClass) libraryPackage.getEClassifier("Book");
		writerClass = (EClass) libraryPackage.getEClassifier("Writer");
	}

	private static ResourceSet resourceSet(EPackage.Registry registry) {
		ResourceSetImpl resourceSet = new ResourceSetImpl();
		resourceSet.setPackageRegistry(new EPackageRegistryImpl(registry));
		return resourceSet;
	}

	@Test
	void createAssignsDeterministicIds() {
		assertThat(ModelOperations.createInstance(dataset, libraryClass, limits)).isEqualTo("o1");
		assertThat(ModelOperations.createInstance(dataset, bookClass, limits)).isEqualTo("o2");
		assertThat(dataset.objectCount()).isEqualTo(2);
		assertThat(dataset.recipeSnapshot()).hasSize(2);
	}

	@Test
	void setCoercesAttributeValues() {
		String bookId = ModelOperations.createInstance(dataset, bookClass, limits);
		ModelOperations.modifyFeature(dataset, bookId, "title", "set", "Dune", null, limits);
		ModelOperations.modifyFeature(dataset, bookId, "pages", "set", 412, null, limits);
		ModelOperations.modifyFeature(dataset, bookId, "genre", "set", "SCIFI", null, limits);
		EObject book = dataset.requireObject(bookId);
		assertThat(book.eGet(bookClass.getEStructuralFeature("title"))).isEqualTo("Dune");
		assertThat(book.eGet(bookClass.getEStructuralFeature("pages"))).isEqualTo(412);
		assertThat(String.valueOf(book.eGet(bookClass.getEStructuralFeature("genre")))).isEqualTo("SCIFI");
	}

	@Test
	void setRejectsUnconvertibleValues() {
		String bookId = ModelOperations.createInstance(dataset, bookClass, limits);
		assertThatThrownBy(() -> ModelOperations.modifyFeature(dataset, bookId, "pages", "set", "not-a-number", null, limits))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("Cannot convert");
		assertThatThrownBy(() -> ModelOperations.modifyFeature(dataset, bookId, "genre", "set", "WESTERN", null, limits))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("Cannot convert");
	}

	@Test
	void unknownFeatureListsAvailableFeatures() {
		String bookId = ModelOperations.createInstance(dataset, bookClass, limits);
		assertThatThrownBy(() -> ModelOperations.modifyFeature(dataset, bookId, "nope", "set", "x", null, limits))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("title")
				.hasMessageContaining("pages");
	}

	@Test
	void manyValuedFeaturesRequireAdd() {
		String bookId = ModelOperations.createInstance(dataset, bookClass, limits);
		assertThatThrownBy(() -> ModelOperations.modifyFeature(dataset, bookId, "tags", "set", "epic", null, limits))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("'add'");
		ModelOperations.modifyFeature(dataset, bookId, "tags", "add", "epic", null, limits);
		ModelOperations.modifyFeature(dataset, bookId, "tags", "add", "classic", 0, limits);
		EObject book = dataset.requireObject(bookId);
		@SuppressWarnings("unchecked")
		EList<Object> tags = (EList<Object>) book.eGet(bookClass.getEStructuralFeature("tags"));
		assertThat(tags).containsExactly("classic", "epic");
		ModelOperations.modifyFeature(dataset, bookId, "tags", "remove", null, 0, limits);
		assertThat(tags).containsExactly("epic");
	}

	@Test
	void referencesResolveByObjectId() {
		String libraryId = ModelOperations.createInstance(dataset, libraryClass, limits);
		String bookId = ModelOperations.createInstance(dataset, bookClass, limits);
		String writerId = ModelOperations.createInstance(dataset, writerClass, limits);
		ModelOperations.modifyFeature(dataset, libraryId, "books", "add", bookId, null, limits);
		ModelOperations.modifyFeature(dataset, bookId, "author", "set", writerId, null, limits);
		ModelOperations.modifyFeature(dataset, libraryId, "featuredBook", "set", bookId, null, limits);
		EObject library = dataset.requireObject(libraryId);
		EObject book = dataset.requireObject(bookId);
		assertThat(book.eContainer()).isSameAs(library);
		assertThat(library.eGet(libraryClass.getEStructuralFeature("featuredBook"))).isSameAs(book);
		// contained book is no longer a root
		assertThat(dataset.roots()).hasSize(2);
	}

	@Test
	void referenceTypeMismatchIsRejected() {
		String libraryId = ModelOperations.createInstance(dataset, libraryClass, limits);
		String writerId = ModelOperations.createInstance(dataset, writerClass, limits);
		assertThatThrownBy(() -> ModelOperations.modifyFeature(dataset, libraryId, "featuredBook", "set", writerId, null, limits))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("not compatible");
	}

	@Test
	void referenceToUnknownObjectIsRejected() {
		String libraryId = ModelOperations.createInstance(dataset, libraryClass, limits);
		assertThatThrownBy(() -> ModelOperations.modifyFeature(dataset, libraryId, "featuredBook", "set", "o99", null, limits))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("Unknown objectId");
	}

	@Test
	void deleteClearsCrossReferencesOverRoots() {
		String libraryId = ModelOperations.createInstance(dataset, libraryClass, limits);
		String bookId = ModelOperations.createInstance(dataset, bookClass, limits);
		ModelOperations.modifyFeature(dataset, libraryId, "featuredBook", "set", bookId, null, limits);
		ModelOperations.deleteInstance(dataset, bookId, limits);
		EObject library = dataset.requireObject(libraryId);
		assertThat(library.eGet(libraryClass.getEStructuralFeature("featuredBook"))).isNull();
		assertThat(dataset.objectCount()).isEqualTo(1);
	}

	@Test
	void deleteRemovesContainmentSubtreeFromDataset() {
		String libraryId = ModelOperations.createInstance(dataset, libraryClass, limits);
		String bookId = ModelOperations.createInstance(dataset, bookClass, limits);
		ModelOperations.modifyFeature(dataset, libraryId, "books", "add", bookId, null, limits);
		ModelOperations.deleteInstance(dataset, libraryId, limits);
		assertThat(dataset.objectCount()).isZero();
	}

	@Test
	void objectCapIsEnforced() {
		DatasetLimits tight = new DatasetLimits(16, 2, 100, 100, 1024, 1024, 60_000L);
		ModelOperations.createInstance(dataset, bookClass, tight);
		ModelOperations.createInstance(dataset, bookClass, tight);
		assertThatThrownBy(() -> ModelOperations.createInstance(dataset, bookClass, tight))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("limit");
	}

	@Test
	void replayHonoursCurrentAllowListAndReproducesState() {
		String libraryId = ModelOperations.createInstance(dataset, libraryClass, limits);
		String bookId = ModelOperations.createInstance(dataset, bookClass, limits);
		ModelOperations.modifyFeature(dataset, libraryId, "name", "set", "City Library", null, limits);
		ModelOperations.modifyFeature(dataset, libraryId, "books", "add", bookId, null, limits);
		ModelOperations.modifyFeature(dataset, bookId, "title", "set", "Dune", null, limits);
		List<RecipeOp> recipe = dataset.recipeSnapshot();

		dataset.clearObjects();
		ModelOperations.replay(dataset, recipe, guard, limits, (ds, id, cls, data) -> {
			throw new ToolException("no codec in unit test");
		});
		EObject library = dataset.requireObject(libraryId);
		assertThat(library.eGet(libraryClass.getEStructuralFeature("name"))).isEqualTo("City Library");
		EObject book = dataset.requireObject(bookId);
		assertThat(book.eGet(bookClass.getEStructuralFeature("title"))).isEqualTo("Dune");
		assertThat(book.eContainer()).isSameAs(library);

		// a guard without Book must reject the same recipe
		ModelGuard restricted = new ModelGuard(TestModels.registryWith(libraryPackage), Set.of(TestModels.NS_URI), Set.of(TestModels.LIBRARY));
		Dataset fresh = new Dataset("fresh", resourceSet(TestModels.registryWith(libraryPackage)), null);
		assertThatThrownBy(() -> ModelOperations.replay(fresh, recipe, restricted, limits, (ds, id, cls, data) -> {
			throw new ToolException("no codec in unit test");
		})).isInstanceOf(ToolException.class).hasMessageContaining("not allow-listed");
	}
}
