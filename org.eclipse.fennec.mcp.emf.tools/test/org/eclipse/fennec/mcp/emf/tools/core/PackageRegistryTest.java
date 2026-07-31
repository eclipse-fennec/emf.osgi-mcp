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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.fennec.emf.osgi.metadata.MetadataWhiteboard;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests the registration policy, cap/LRU eviction, snapshot isolation and
 * session scoping of the {@link PackageRegistry}.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
class PackageRegistryTest {

	private static EPackage pkg(String nsUri) {
		EPackage ePackage = EcoreFactory.eINSTANCE.createEPackage();
		ePackage.setName(nsUri.substring(nsUri.lastIndexOf('/') + 1));
		ePackage.setNsPrefix(ePackage.getName());
		ePackage.setNsURI(nsUri);
		EClass thing = EcoreFactory.eINSTANCE.createEClass();
		thing.setName("Thing");
		ePackage.getEClassifiers().add(thing);
		return ePackage;
	}

	private static void pause() {
		try {
			Thread.sleep(5);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Test
	void emptyAllowListDeniesEverything() {
		PackageRegistry registry = new PackageRegistry(Set.of(), Set.of(), 100);
		assertThat(registry.isRegistrable("http://example.org/a")).isFalse();
		assertThatThrownBy(() -> registry.register("s1", pkg("http://example.org/a")))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("not allow-listed");
	}

	@Test
	void wildcardAllowsAllNonReserved() {
		PackageRegistry registry = new PackageRegistry(Set.of("*"), Set.of(), 100);
		EPackage registered = registry.register("s1", pkg("http://example.org/a"));
		assertThat(registered.getNsURI()).isEqualTo("http://example.org/a");
		assertThat(registry.resolve("s1", "http://example.org/a")).isSameAs(registered);
	}

	@Test
	void prefixWildcardAndDenyList() {
		PackageRegistry registry = new PackageRegistry(Set.of("http://acme.com/*"), Set.of("http://acme.com/secret*"), 100);
		assertThat(registry.isRegistrable("http://acme.com/models/foo")).isTrue();
		assertThat(registry.isRegistrable("http://acme.com/secret/x")).isFalse();
		assertThat(registry.isRegistrable("http://other.org/x")).isFalse();
		assertThatThrownBy(() -> registry.register("s1", pkg("http://acme.com/secret/x")))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("deny-listed");
	}

	@Test
	void reservedNamespacesAreNeverRegistrable() {
		PackageRegistry registry = new PackageRegistry(Set.of("*"), Set.of(), 100);
		assertThat(registry.isRegistrable(EcorePackage.eNS_URI)).isFalse();
		assertThat(registry.isRegistrable("http://www.eclipse.org/emf/2003/XMLType")).isFalse();
		assertThat(registry.isRegistrable("http://www.eclipse.org/emf/2002/GenModel")).isFalse();
		assertThatThrownBy(() -> registry.register("s1", pkg(EcorePackage.eNS_URI)))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("reserved");
	}

	@Test
	void registerStoresAFrozenCopy() {
		PackageRegistry registry = new PackageRegistry(Set.of("*"), Set.of(), 100);
		EPackage source = pkg("http://example.org/a");
		EPackage registered = registry.register("s1", source);
		assertThat(registered).isNotSameAs(source);
		// mutating the source afterwards must not change the registered snapshot
		EClass extra = EcoreFactory.eINSTANCE.createEClass();
		extra.setName("Added");
		source.getEClassifiers().add(extra);
		assertThat(registry.resolve("s1", "http://example.org/a").getEClassifier("Added")).isNull();
		assertThat(registry.resolveClassifier("s1", "http://example.org/a", "Thing")).isNotNull();
	}

	@Test
	void reRegisterReplacesWithoutCountingAgainstCap() {
		PackageRegistry registry = new PackageRegistry(Set.of("*"), Set.of(), 1);
		registry.register("s1", pkg("http://example.org/a"));
		// re-registering the same nsURI stays at size 1 and does not evict
		registry.register("s1", pkg("http://example.org/a"));
		assertThat(registry.list("s1")).extracting(EPackage::getNsURI).containsExactly("http://example.org/a");
	}

	@Test
	void capEvictsLeastRecentlyModified() {
		PackageRegistry registry = new PackageRegistry(Set.of("*"), Set.of(), 2);
		registry.register("s1", pkg("http://example.org/a"));
		pause();
		registry.register("s1", pkg("http://example.org/b"));
		pause();
		registry.resolve("s1", "http://example.org/a"); // touch a -> b becomes the oldest
		pause();
		registry.register("s1", pkg("http://example.org/c")); // cap exceeded -> evict b
		assertThat(registry.resolve("s1", "http://example.org/b")).isNull();
		assertThat(registry.resolve("s1", "http://example.org/a")).isNotNull();
		assertThat(registry.resolve("s1", "http://example.org/c")).isNotNull();
		assertThat(registry.list("s1")).hasSize(2);
	}

	@Test
	void unregisterAndRekey() {
		PackageRegistry registry = new PackageRegistry(Set.of("*"), Set.of(), 100);
		registry.register("s1", pkg("http://example.org/a"));
		EPackage rekeyed = registry.rekey("s1", "http://example.org/a", "http://example.org/b");
		assertThat(rekeyed.getNsURI()).isEqualTo("http://example.org/b");
		assertThat(registry.resolve("s1", "http://example.org/a")).isNull();
		assertThat(registry.resolve("s1", "http://example.org/b")).isSameAs(rekeyed);
		assertThat(registry.unregister("s1", "http://example.org/b")).isTrue();
		assertThat(registry.unregister("s1", "http://example.org/b")).isFalse();
	}

	@Test
	void rekeyToReservedIsRejected() {
		PackageRegistry registry = new PackageRegistry(Set.of("*"), Set.of(), 100);
		registry.register("s1", pkg("http://example.org/a"));
		assertThatThrownBy(() -> registry.rekey("s1", "http://example.org/a", EcorePackage.eNS_URI))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("reserved");
	}

	@Test
	void packagesAreSessionScoped() {
		PackageRegistry registry = new PackageRegistry(Set.of("*"), Set.of(), 100);
		registry.register("s1", pkg("http://example.org/a"));
		assertThat(registry.resolve("s2", "http://example.org/a")).isNull();
		assertThat(registry.list("s2")).isEmpty();
		assertThat(registry.list("s1")).hasSize(1);
	}

	@Test
	void missingSessionIsRejected() {
		PackageRegistry registry = new PackageRegistry(Set.of("*"), Set.of(), 100);
		assertThatThrownBy(() -> registry.register(null, pkg("http://example.org/a")))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("session");
	}

	@Test
	void registeredPackagesAreAnnouncedToTheMetadataWhiteboard() {
		PackageRegistry registry = new PackageRegistry(Set.of("*"), Set.of(), 100);
		MetadataWhiteboard whiteboard = mock(MetadataWhiteboard.class);
		registry.setMetadataWhiteboard(whiteboard);

		EPackage registered = registry.register("s1", pkg("http://example.org/a"));
		verify(whiteboard).registerPackage(registered);

		registry.unregister("s1", "http://example.org/a");
		verify(whiteboard).unregisterPackage(registered);
	}

	@Test
	void reRegisterRetractsThePreviousSnapshot() {
		PackageRegistry registry = new PackageRegistry(Set.of("*"), Set.of(), 100);
		MetadataWhiteboard whiteboard = mock(MetadataWhiteboard.class);
		registry.setMetadataWhiteboard(whiteboard);

		EPackage first = registry.register("s1", pkg("http://example.org/a"));
		EPackage second = registry.register("s1", pkg("http://example.org/a"));
		verify(whiteboard).unregisterPackage(first);
		verify(whiteboard).registerPackage(second);
	}

	@Test
	void capEvictionRetractsTheVictim() {
		PackageRegistry registry = new PackageRegistry(Set.of("*"), Set.of(), 1);
		MetadataWhiteboard whiteboard = mock(MetadataWhiteboard.class);
		registry.setMetadataWhiteboard(whiteboard);

		EPackage victim = registry.register("s1", pkg("http://example.org/a"));
		pause();
		registry.register("s1", pkg("http://example.org/b"));
		verify(whiteboard).unregisterPackage(victim);
	}

	@Test
	void rekeyReAnnouncesUnderTheNewNamespace() {
		PackageRegistry registry = new PackageRegistry(Set.of("*"), Set.of(), 100);
		MetadataWhiteboard whiteboard = mock(MetadataWhiteboard.class);
		registry.setMetadataWhiteboard(whiteboard);

		EPackage registered = registry.register("s1", pkg("http://example.org/a"));
		registry.rekey("s1", "http://example.org/a", "http://example.org/b");
		verify(whiteboard).unregisterPackage(registered);
		ArgumentCaptor<EPackage> announced = ArgumentCaptor.forClass(EPackage.class);
		verify(whiteboard, times(2)).registerPackage(announced.capture());
		assertThat(announced.getValue().getNsURI()).isEqualTo("http://example.org/b");
	}

	@Test
	void lateWhiteboardBindAnnouncesExistingPackages() {
		PackageRegistry registry = new PackageRegistry(Set.of("*"), Set.of(), 100);
		EPackage registered = registry.register("s1", pkg("http://example.org/a"));

		MetadataWhiteboard whiteboard = mock(MetadataWhiteboard.class);
		registry.setMetadataWhiteboard(whiteboard);
		verify(whiteboard).registerPackage(registered);
	}

	@Test
	void whiteboardFailuresNeverBreakRegistration() {
		PackageRegistry registry = new PackageRegistry(Set.of("*"), Set.of(), 100);
		MetadataWhiteboard whiteboard = mock(MetadataWhiteboard.class);
		when(whiteboard.registerPackage(any())).thenThrow(new IllegalStateException("boom"));
		registry.setMetadataWhiteboard(whiteboard);

		EPackage registered = registry.register("s1", pkg("http://example.org/a"));
		assertThat(registry.resolve("s1", "http://example.org/a")).isSameAs(registered);
	}

	@Test
	void withoutWhiteboardNothingIsAnnounced() {
		PackageRegistry registry = new PackageRegistry(Set.of("*"), Set.of(), 100);
		MetadataWhiteboard whiteboard = mock(MetadataWhiteboard.class);
		registry.register("s1", pkg("http://example.org/a"));
		registry.setMetadataWhiteboard(whiteboard);
		registry.unsetMetadataWhiteboard(whiteboard);
		registry.register("s1", pkg("http://example.org/b"));
		verify(whiteboard, never()).registerPackage(argNsUri("http://example.org/b"));
	}

	private static EPackage argNsUri(String nsUri) {
		return argThat(p -> p != null && nsUri.equals(p.getNsURI()));
	}
}
