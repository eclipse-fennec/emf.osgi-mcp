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
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.impl.EPackageRegistryImpl;
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

	@Test
	void builtinEcoreDatatypesAreAlwaysResolvableAsTypes() {
		// no package needs allow-listing to use the built-in Ecore datatypes as types
		ModelGuard guard = new ModelGuard(registry, Set.of(), Set.of());
		assertThat(guard.requireAllowedClassifier(EcorePackage.eNS_URI + "#//EString"))
				.isSameAs(EcorePackage.eINSTANCE.getEString());
		assertThat(guard.resolverFor("s").resolveClassifier(EcorePackage.eNS_URI + "#//EInt"))
				.isSameAs(EcorePackage.eINSTANCE.getEInt());
	}

	@Test
	void requireAllowedClassifierPermitsAbstractAndNonEClass() {
		// unlike requireAllowedEClass, typing resolution accepts abstract classes and datatypes/enums,
		// and does not require the class allow-list (only the package must be allow-listed)
		ModelGuard guard = new ModelGuard(registry, Set.of(TestModels.NS_URI), Set.of());
		assertThat(guard.requireAllowedClassifier(TestModels.ABSTRACT_ITEM).getName()).isEqualTo("AbstractItem");
		assertThat(guard.requireAllowedClassifier(TestModels.NS_URI + "#//Genre").getName()).isEqualTo("Genre");
	}

	@Test
	void requireAllowedClassifierEnforcesPackageAllowList() {
		ModelGuard guard = new ModelGuard(registry, Set.of(), Set.of());
		assertThatThrownBy(() -> guard.requireAllowedClassifier(TestModels.BOOK))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("not allow-listed");
		assertThatThrownBy(() -> guard.requireAllowedClassifier("nonsense"))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("<nsURI>#//<Name>");
	}

	@Test
	void sessionLocalPackageIsInstantiableWithoutAllowList() {
		// deny-all OSGi allow-lists, but a registered session package is trusted
		PackageRegistry sessionPackages = new PackageRegistry(Set.of("*"), Set.of(), 100);
		sessionPackages.register("s1", libraryPackage);
		ModelGuard guard = new ModelGuard(registry, sessionPackages, Set.of(), Set.of());

		EClass book = guard.resolverFor("s1").resolveConcreteEClass(TestModels.BOOK);
		assertThat(book.getName()).isEqualTo("Book");
		// the resolved class comes from the registered snapshot, not the OSGi package (shadowing precedence)
		assertThat(book).isNotSameAs(libraryPackage.getEClassifier("Book"));
	}

	@Test
	void sessionLocalResolutionIsSessionScoped() {
		PackageRegistry sessionPackages = new PackageRegistry(Set.of("*"), Set.of(), 100);
		sessionPackages.register("s1", libraryPackage);
		ModelGuard guard = new ModelGuard(registry, sessionPackages, Set.of(), Set.of());
		// another session has no such package and the OSGi allow-list is empty
		assertThatThrownBy(() -> guard.resolverFor("other").resolveConcreteEClass(TestModels.BOOK))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("not allow-listed");
	}

	@Test
	void sessionLocalAbstractClassIsNotInstantiable() {
		PackageRegistry sessionPackages = new PackageRegistry(Set.of("*"), Set.of(), 100);
		sessionPackages.register("s1", libraryPackage);
		ModelGuard guard = new ModelGuard(registry, sessionPackages, Set.of(), Set.of());
		assertThatThrownBy(() -> guard.resolverFor("s1").resolveConcreteEClass(TestModels.ABSTRACT_ITEM))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("abstract");
		// but it resolves fine as a type
		assertThat(guard.resolverFor("s1").resolveClassifier(TestModels.ABSTRACT_ITEM).getName()).isEqualTo("AbstractItem");
	}

	// --- pattern allow-lists (issue #30) ---------------------------------------

	@Test
	void anExactAllowListStillBehavesExactly() {
		// The change must be inert for every deployment that uses no '*'.
		ModelGuard guard = new ModelGuard(registry, Set.of(TestModels.NS_URI), Set.of(TestModels.BOOK));

		assertThat(guard.allowedPackages()).containsExactly(libraryPackage);
		assertThat(guard.requireAllowedPackage(TestModels.NS_URI)).isSameAs(libraryPackage);
		assertThatThrownBy(() -> guard.requireAllowedPackage(TestModels.NS_URI + "/extra"))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("not allow-listed");
	}

	@Test
	void aPrefixPatternListsAndReadsTheMatchingPackage() {
		ModelGuard guard = new ModelGuard(registry, Set.of("http://example.org/*"),
				Set.of(TestModels.NS_URI + "#//*"));

		assertThat(guard.allowedPackages()).containsExactly(libraryPackage);
		assertThat(guard.requireAllowedPackage(TestModels.NS_URI)).isSameAs(libraryPackage);
		// and the package being readable is what makes its classes readable too
		assertThat(guard.requireAllowedEClass(TestModels.BOOK).getName()).isEqualTo("Book");
		assertThat(guard.allowedConcreteClasses(libraryPackage)).extracting(EClass::getName)
				.contains("Book", "Library", "Writer");
	}

	@Test
	void aPrefixPatternIsAnchoredOnTheWholeNamespace() {
		EPackage lookalike = TestModels.libraryPackage();
		lookalike.setNsURI("http://evil.example/http://example.org/library");
		ModelGuard guard = new ModelGuard(TestModels.registryWith(libraryPackage, lookalike),
				Set.of("http://example.org/*"), Set.of());

		assertThat(guard.allowedPackages()).containsExactly(libraryPackage);
		assertThatThrownBy(() -> guard.requireAllowedPackage(lookalike.getNsURI()))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("not allow-listed");
	}

	@Test
	void bareWildcardExposesEveryRegisteredPackage() {
		EPackage other = TestModels.annotatedPackage(libraryPackage);
		ModelGuard guard = new ModelGuard(TestModels.registryWith(libraryPackage, other), Set.of("*"), Set.of("*"));

		// sorted by nsURI: .../library before .../uplink
		assertThat(guard.allowedPackages()).containsExactly(libraryPackage, other);
		assertThat(guard.requireAllowedEClass(TestModels.BOOK).getName()).isEqualTo("Book");
	}

	@Test
	void allowedPackagesStaysSortedByNamespaceUri() {
		EPackage other = TestModels.annotatedPackage(libraryPackage);
		ModelGuard guard = new ModelGuard(TestModels.registryWith(libraryPackage, other), Set.of("*"), Set.of());

		assertThat(guard.allowedPackages()).extracting(EPackage::getNsURI).isSorted();
	}

	@Test
	void aPatternThatMatchesNothingListsNothing() {
		ModelGuard guard = new ModelGuard(registry, Set.of("http://nowhere.example/*"), Set.of("*"));

		assertThat(guard.allowedPackages()).isEmpty();
		assertThatThrownBy(() -> guard.requireAllowedPackage(TestModels.NS_URI))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("not allow-listed");
	}

	@Test
	void aWidePackagePatternAloneExposesNoClass() {
		// The two lists stay independent: this is the decision that keeps '*' on the
		// package list from being an accidental instantiation grant.
		ModelGuard guard = new ModelGuard(registry, Set.of("*"), Set.of());

		assertThat(guard.allowedPackages()).containsExactly(libraryPackage);
		assertThat(guard.allowedConcreteClasses(libraryPackage)).isEmpty();
		assertThatThrownBy(() -> guard.requireAllowedEClass(TestModels.BOOK))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("not allow-listed");
	}

	@Test
	void aClassPatternIsStillGatedOnItsPackage() {
		ModelGuard guard = new ModelGuard(registry, Set.of(), Set.of("*"));

		assertThatThrownBy(() -> guard.requireAllowedEClass(TestModels.BOOK))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("not allow-listed");
		assertThat(guard.isClassAllowed((EClass) libraryPackage.getEClassifier("Book"))).isFalse();
	}

	@Test
	void aPackageRegisteredAfterConfigurationIsListedAndReadable() {
		// The model.atlas mirror case: nobody edits configuration when a scope
		// publishes a package, so allowedPackages() has to filter the live registry.
		EPackage.Registry live = TestModels.registryWith(libraryPackage);
		ModelGuard guard = new ModelGuard(live, Set.of("http://example.org/*"), Set.of("http://example.org/*"));
		assertThat(guard.allowedPackages()).containsExactly(libraryPackage);

		EPackage late = TestModels.annotatedPackage(libraryPackage);
		live.put(late.getNsURI(), late);

		assertThat(guard.allowedPackages()).contains(late);
		assertThat(guard.requireAllowedPackage(late.getNsURI())).isSameAs(late);
	}

	@Test
	void anEmptyPackageListDeniesEverythingEvenWithAWideClassList() {
		ModelGuard guard = new ModelGuard(registry, Set.of(), Set.of("*"));

		assertThat(guard.allowedPackages()).isEmpty();
	}

	@Test
	void exactEntriesAreListedEvenWhenTheRegistryDoesNotEnumerate() {
		// A registry that delegates without overriding keySet() reports nothing of its
		// delegate. Exact entries must survive that; only patterns may depend on
		// enumeration.
		EPackage.Registry blind = new EPackageRegistryImpl(TestModels.registryWith(libraryPackage));
		ModelGuard guard = new ModelGuard(blind, Set.of(TestModels.NS_URI), Set.of(TestModels.BOOK));

		assertThat(blind.keySet()).isEmpty();
		assertThat(guard.allowedPackages()).containsExactly(libraryPackage);
		assertThat(guard.requireAllowedEClass(TestModels.BOOK).getName()).isEqualTo("Book");
	}
}
