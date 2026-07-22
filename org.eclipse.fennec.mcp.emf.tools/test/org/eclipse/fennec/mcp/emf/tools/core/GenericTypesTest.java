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
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EGenericType;
import org.eclipse.emf.ecore.ETypeParameter;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.impl.EPackageRegistryImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the recursive {@link GenericTypes} parser.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
class GenericTypesTest {

	private static final String ESTRING = EcorePackage.eNS_URI + "#//EString";

	private Dataset dataset;
	private ClassifierResolver resolver;
	private String listClassId;
	private String typeParamId;

	@BeforeEach
	void setUp() {
		dataset = new Dataset("d", new ResourceSetImpl(), null);
		DatasetLimits limits = DatasetLimits.defaults();
		EClass list = EcoreFactory.eINSTANCE.createEClass();
		list.setName("EList");
		ETypeParameter param = EcoreFactory.eINSTANCE.createETypeParameter();
		param.setName("E");
		list.getETypeParameters().add(param);
		listClassId = EcoreAuthoring.put(dataset, list, limits);
		typeParamId = EcoreAuthoring.put(dataset, param, limits);
		resolver = new ModelGuard(new EPackageRegistryImpl(), Set.of(), Set.of()).resolverFor("s");
	}

	@Test
	void parsesClassifierReference() {
		EGenericType gt = GenericTypes.parse(dataset, resolver, Map.of("classifier", ESTRING));
		assertThat(gt.getEClassifier()).isSameAs(EcorePackage.eINSTANCE.getEString());
		assertThat(gt.getETypeParameter()).isNull();
	}

	@Test
	void parsesTypeParameterReference() {
		EGenericType gt = GenericTypes.parse(dataset, resolver, Map.of("typeParameter", typeParamId));
		assertThat(gt.getETypeParameter().getName()).isEqualTo("E");
		assertThat(gt.getEClassifier()).isNull();
	}

	@Test
	void parsesParameterizedTypeWithArguments() {
		// EList<EString>
		EGenericType gt = GenericTypes.parse(dataset, resolver,
				Map.of("classifier", listClassId, "typeArguments", List.of(Map.of("classifier", ESTRING))));
		assertThat(((EClass) gt.getEClassifier()).getName()).isEqualTo("EList");
		assertThat(gt.getETypeArguments()).hasSize(1);
		assertThat(gt.getETypeArguments().get(0).getEClassifier()).isSameAs(EcorePackage.eINSTANCE.getEString());
	}

	@Test
	void parsesWildcardWithUpperBound() {
		// ? extends EString
		EGenericType gt = GenericTypes.parse(dataset, resolver, Map.of("upperBound", Map.of("classifier", ESTRING)));
		assertThat(gt.getEClassifier()).isNull();
		assertThat(gt.getETypeParameter()).isNull();
		assertThat(gt.getEUpperBound().getEClassifier()).isSameAs(EcorePackage.eINSTANCE.getEString());
	}

	@Test
	void rejectsBothClassifierAndTypeParameter() {
		assertThatThrownBy(() -> GenericTypes.parse(dataset, resolver, Map.of("classifier", ESTRING, "typeParameter", typeParamId)))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("at most one");
	}

	@Test
	void rejectsTypeArgumentsWithoutClassifier() {
		assertThatThrownBy(() -> GenericTypes.parse(dataset, resolver,
				Map.of("typeParameter", typeParamId, "typeArguments", List.of(Map.of("classifier", ESTRING)))))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("typeArguments");
	}

	@Test
	void rejectsNonObjectSpec() {
		assertThatThrownBy(() -> GenericTypes.parse(dataset, resolver, "EString"))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("JSON object");
	}
}
