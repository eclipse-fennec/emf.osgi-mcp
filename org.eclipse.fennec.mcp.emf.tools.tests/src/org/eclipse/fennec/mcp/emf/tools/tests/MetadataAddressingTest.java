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
package org.eclipse.fennec.mcp.emf.tools.tests;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.fennec.emf.osgi.metadata.MetadataWhiteboard;
import org.eclipse.fennec.emf.osgi.model.metadata.PackageMetadata;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.osgi.framework.BundleContext;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.common.annotation.InjectService;
import org.osgi.test.junit5.context.BundleContextExtension;
import org.osgi.test.junit5.service.ServiceExtension;

/**
 * Addressing a model version by fingerprint, through the real DS wiring.
 * <p>
 * Two diverging versions are registered under one namespace in the live
 * {@code MetadataWhiteboard} and <b>neither is put in the EPackage.Registry</b>, which is
 * the situation the registry cannot represent: it maps one nsURI to one package. So a
 * fingerprint here does not merely disambiguate, it reaches a model the registry-based
 * resolution has no way to return at all.
 * <p>
 * The unit tests cover the same rules with the field set by reflection. What this adds is
 * that {@code ModelGuard}'s {@code MetadataService} reference is actually satisfiable in a
 * deployed runtime - the reference is mandatory, so if it were not, the guard would never
 * activate and {@code awaitTool} would time out before a single rule was reached.
 */
@ExtendWith(BundleContextExtension.class)
@ExtendWith(ServiceExtension.class)
class MetadataAddressingTest extends AbstractEMFToolsTest {

	private static final String NS_URI = "https://example.org/osgi/library";
	private static final String BOOK = NS_URI + "#//Book";

	@Test
	void aFingerprintReachesAVersionTheRegistryCannotHold(@InjectBundleContext BundleContext context,
			@InjectService ConfigurationAdmin cm, @InjectService MetadataWhiteboard whiteboard) throws IOException {
		String plain = register(whiteboard, libraryPackage(false));
		String withIsbn = register(whiteboard, libraryPackage(true));
		assertNotEquals(plain, withIsbn, "diverging content must fingerprint differently");

		configureGuard(cm, "*", "*");
		MCPTool describeEClass = awaitTool(context, "describe_eclass");

		String described = call(describeEClass, Map.of("eClass", BOOK, "fingerprint", withIsbn));

		// 'isbn' exists only in the second version, and nothing was ever put in the
		// EPackage.Registry: the read followed the fingerprint into the metadata layer.
		assertTrue(described.contains("isbn"), "expected the addressed version's feature, got: " + described);
		assertTrue(described.contains(withIsbn), "expected the version to be reported back, got: " + described);

		String other = call(describeEClass, Map.of("eClass", BOOK, "fingerprint", plain));
		assertTrue(!other.contains("isbn"), "the other version must not carry it, got: " + other);
	}

	@Test
	void anAmbiguousNsUriIsRefusedRatherThanGuessed(@InjectBundleContext BundleContext context,
			@InjectService ConfigurationAdmin cm, @InjectService MetadataWhiteboard whiteboard) throws IOException {
		String plain = register(whiteboard, libraryPackage(false));
		String withIsbn = register(whiteboard, libraryPackage(true));

		configureGuard(cm, "*", "*");
		MCPTool listMetamodel = awaitTool(context, "list_metamodel");

		String error = callExpectingError(listMetamodel, Map.of("nsURI", NS_URI));

		assertTrue(error.contains("2 registered model versions"), "expected the refusal, got: " + error);
		assertTrue(error.contains(plain) && error.contains(withIsbn),
				"the refusal must name both fingerprints, got: " + error);
	}

	private static String register(MetadataWhiteboard whiteboard, EPackage ePackage) {
		return whiteboard.registerPackage(ePackage).map(PackageMetadata::getModelFingerprint).orElseThrow();
	}

	/** One namespace, two contents: the second adds an attribute the first does not have. */
	private static EPackage libraryPackage(boolean withIsbn) {
		EcoreFactory factory = EcoreFactory.eINSTANCE;
		EPackage library = factory.createEPackage();
		library.setName("library");
		library.setNsURI(NS_URI);
		library.setNsPrefix("library");

		EClass book = factory.createEClass();
		book.setName("Book");
		EAttribute title = factory.createEAttribute();
		title.setName("title");
		title.setEType(EcorePackage.Literals.ESTRING);
		book.getEStructuralFeatures().add(title);
		if (withIsbn) {
			EAttribute isbn = factory.createEAttribute();
			isbn.setName("isbn");
			isbn.setEType(EcorePackage.Literals.ESTRING);
			book.getEStructuralFeatures().add(isbn);
		}
		library.getEClassifiers().add(book);
		return library;
	}
}
