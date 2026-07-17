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

import java.util.Set;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EPackage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the deny-all allow-list enforcement of the {@link ModelGuard}.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
class ModelGuardTest {

	private EPackage libraryPackage;
	private EPackage.Registry registry;

	@BeforeEach
	void setUp() {
		libraryPackage = TestModels.libraryPackage();
		registry = TestModels.registryWith(libraryPackage);
	}

	@Test
	void emptyAllowListsDenyEverything() {
		ModelGuard guard = new ModelGuard(registry, Set.of(), Set.of());
		assertThat(guard.allowedPackages()).isEmpty();
		assertThatThrownBy(() -> guard.requireAllowedPackage(TestModels.NS_URI))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("not allow-listed");
		assertThatThrownBy(() -> guard.requireAllowedEClass(TestModels.BOOK))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("not allow-listed");
	}

	@Test
	void packageListedButClassNotIsDenied() {
		ModelGuard guard = new ModelGuard(registry, Set.of(TestModels.NS_URI), Set.of());
		assertThat(guard.requireAllowedPackage(TestModels.NS_URI)).isSameAs(libraryPackage);
		assertThat(guard.allowedConcreteClasses(libraryPackage)).isEmpty();
		assertThatThrownBy(() -> guard.requireAllowedEClass(TestModels.BOOK))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("not allow-listed");
	}

	@Test
	void classListedButPackageNotIsDenied() {
		ModelGuard guard = new ModelGuard(registry, Set.of(), Set.of(TestModels.BOOK));
		assertThatThrownBy(() -> guard.requireAllowedEClass(TestModels.BOOK))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("not allow-listed");
	}

	@Test
	void bothListedIsAllowed() {
		ModelGuard guard = new ModelGuard(registry, Set.of(TestModels.NS_URI), Set.of(TestModels.BOOK));
		EClass book = guard.requireAllowedEClass(TestModels.BOOK);
		assertThat(book.getName()).isEqualTo("Book");
		assertThat(guard.isClassAllowed(book)).isTrue();
		assertThat(guard.allowedConcreteClasses(libraryPackage)).extracting(EClass::getName).containsExactly("Book");
	}

	@Test
	void abstractClassesAreNotInstantiable() {
		ModelGuard guard = new ModelGuard(registry, Set.of(TestModels.NS_URI), Set.of(TestModels.ABSTRACT_ITEM));
		assertThatThrownBy(() -> guard.requireAllowedEClass(TestModels.ABSTRACT_ITEM))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("abstract");
	}

	@Test
	void malformedClassReferencesAreRejected() {
		ModelGuard guard = new ModelGuard(registry, Set.of(TestModels.NS_URI), Set.of(TestModels.BOOK));
		assertThatThrownBy(() -> guard.requireAllowedEClass("file:/etc/passwd"))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("<nsURI>#//<ClassName>");
		assertThatThrownBy(() -> guard.requireAllowedEClass(null))
				.isInstanceOf(ToolException.class);
		assertThatThrownBy(() -> guard.requireAllowedEClass("http://other.org#//Book"))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("not allow-listed");
	}

	@Test
	void unknownClassNameListsNothingSensitive() {
		ModelGuard guard = new ModelGuard(registry, Set.of(TestModels.NS_URI), Set.of(TestModels.BOOK));
		assertThatThrownBy(() -> guard.requireAllowedEClass(TestModels.NS_URI + "#//Nope"))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("not an EClass");
	}
}
