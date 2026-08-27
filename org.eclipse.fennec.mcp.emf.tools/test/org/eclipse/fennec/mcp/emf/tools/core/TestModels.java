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

import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EModelElement;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.impl.EPackageRegistryImpl;

/**
 * Dynamic test metamodel (no codegen): a small library model with attributes,
 * enums, many-valued features, containment and cross-references.
 *
 * <pre>
 * Library:  name (required), books (containment many Book),
 *           writers (containment many Writer), featuredBook (ref Book)
 * Book:     title, pages (int), genre (enum), tags (many string), author (ref Writer)
 * Writer:   name
 * Abstract: AbstractItem (abstract, to test deny on abstract classes)
 * </pre>
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
public final class TestModels {

	public static final String NS_URI = "http://example.org/library";
	public static final String LIBRARY = NS_URI + "#//Library";
	public static final String BOOK = NS_URI + "#//Book";
	public static final String WRITER = NS_URI + "#//Writer";
	public static final String ABSTRACT_ITEM = NS_URI + "#//AbstractItem";

	public static final String UPLINK_NS_URI = "http://example.org/uplink";
	public static final String UPLINK_BASE = UPLINK_NS_URI + "#//UplinkBase";
	public static final String UPLINK_A = UPLINK_NS_URI + "#//UplinkA";
	public static final String TYPE_MAPPING_SOURCE = "http://eclipse.org/fennec/codec/typeMapping/uplink";
	public static final String EXTENDED_META_DATA_SOURCE = "http:///org/eclipse/emf/ecore/util/ExtendedMetaData";

	private TestModels() {
	}

	public static EPackage libraryPackage() {
		EcoreFactory factory = EcoreFactory.eINSTANCE;
		EcorePackage ecore = EcorePackage.eINSTANCE;

		EPackage ePackage = factory.createEPackage();
		ePackage.setName("library");
		ePackage.setNsPrefix("lib");
		ePackage.setNsURI(NS_URI);

		EEnum genre = factory.createEEnum();
		genre.setName("Genre");
		EEnumLiteral fantasy = factory.createEEnumLiteral();
		fantasy.setName("FANTASY");
		fantasy.setLiteral("FANTASY");
		fantasy.setValue(0);
		EEnumLiteral scifi = factory.createEEnumLiteral();
		scifi.setName("SCIFI");
		scifi.setLiteral("SCIFI");
		scifi.setValue(1);
		genre.getELiterals().add(fantasy);
		genre.getELiterals().add(scifi);
		ePackage.getEClassifiers().add(genre);

		EClass abstractItem = factory.createEClass();
		abstractItem.setName("AbstractItem");
		abstractItem.setAbstract(true);
		ePackage.getEClassifiers().add(abstractItem);

		EClass writer = factory.createEClass();
		writer.setName("Writer");
		writer.getEStructuralFeatures().add(attribute(factory, "name", ecore.getEString(), 0));
		ePackage.getEClassifiers().add(writer);

		EClass book = factory.createEClass();
		book.setName("Book");
		book.getEStructuralFeatures().add(attribute(factory, "title", ecore.getEString(), 0));
		book.getEStructuralFeatures().add(attribute(factory, "pages", ecore.getEInt(), 0));
		EAttribute genreAttribute = attribute(factory, "genre", null, 0);
		genreAttribute.setEType(genre);
		book.getEStructuralFeatures().add(genreAttribute);
		EAttribute tags = attribute(factory, "tags", ecore.getEString(), 0);
		tags.setUpperBound(-1);
		book.getEStructuralFeatures().add(tags);
		EReference author = factory.createEReference();
		author.setName("author");
		author.setEType(writer);
		book.getEStructuralFeatures().add(author);
		ePackage.getEClassifiers().add(book);

		EClass library = factory.createEClass();
		library.setName("Library");
		library.getEStructuralFeatures().add(attribute(factory, "name", ecore.getEString(), 1));
		EReference books = factory.createEReference();
		books.setName("books");
		books.setEType(book);
		books.setContainment(true);
		books.setUpperBound(-1);
		library.getEStructuralFeatures().add(books);
		EReference writers = factory.createEReference();
		writers.setName("writers");
		writers.setEType(writer);
		writers.setContainment(true);
		writers.setUpperBound(-1);
		library.getEStructuralFeatures().add(writers);
		EReference featuredBook = factory.createEReference();
		featuredBook.setName("featuredBook");
		featuredBook.setEType(book);
		library.getEStructuralFeatures().add(featuredBook);
		ePackage.getEClassifiers().add(library);

		return ePackage;
	}

	/**
	 * A package that exercises everything {@code describe_eclass} cannot report:
	 * EAnnotations in their exact spelling, an abstract class, and a supertype
	 * that lives in another package.
	 *
	 * @param libraryPackage the package owning the foreign supertype
	 * @return the uplink package
	 */
	public static EPackage annotatedPackage(EPackage libraryPackage) {
		EcoreFactory factory = EcoreFactory.eINSTANCE;
		EPackage ePackage = factory.createEPackage();
		ePackage.setName("uplink");
		ePackage.setNsPrefix("uplink");
		ePackage.setNsURI(UPLINK_NS_URI);

		EClass base = factory.createEClass();
		base.setName("UplinkBase");
		base.setAbstract(true);
		annotate(base, TYPE_MAPPING_SOURCE, "typeDiscriminatorPath", "deviceInfo.deviceProfileName");
		ePackage.getEClassifiers().add(base);

		EClass uplinkA = factory.createEClass();
		uplinkA.setName("UplinkA");
		uplinkA.getESuperTypes().add(base);
		// the foreign supertype: only a resolvable nsURI-based href can express this
		uplinkA.getESuperTypes().add((EClass) libraryPackage.getEClassifier("AbstractItem"));
		annotate(uplinkA, TYPE_MAPPING_SOURCE, "typeDiscriminator", "Sensor_A");
		EAttribute battery = attribute(factory, "batteryVoltage", EcorePackage.eINSTANCE.getEDouble(), 0);
		annotate(battery, EXTENDED_META_DATA_SOURCE, "name", "BatV");
		uplinkA.getEStructuralFeatures().add(battery);
		ePackage.getEClassifiers().add(uplinkA);

		return ePackage;
	}

	private static void annotate(EModelElement element, String source, String key, String value) {
		EAnnotation annotation = EcoreFactory.eINSTANCE.createEAnnotation();
		annotation.setSource(source);
		annotation.getDetails().put(key, value);
		element.getEAnnotations().add(annotation);
	}

	public static EPackage.Registry registryWith(EPackage... ePackages) {
		EPackage.Registry registry = new EPackageRegistryImpl();
		for (EPackage ePackage : ePackages) {
			registry.put(ePackage.getNsURI(), ePackage);
		}
		return registry;
	}

	private static EAttribute attribute(EcoreFactory factory, String name, EClassifier type, int lowerBound) {
		EAttribute attribute = factory.createEAttribute();
		attribute.setName(name);
		if (type != null) {
			attribute.setEType(type);
		}
		attribute.setLowerBound(lowerBound);
		return attribute;
	}
}
