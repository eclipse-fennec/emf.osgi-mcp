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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.junit.jupiter.api.Test;

/**
 * Tests the register-time guards of {@link EcoreAuthoring}.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
class EcoreAuthoringTest {

	private static EPackage authored() {
		EPackage ePackage = EcoreFactory.eINSTANCE.createEPackage();
		ePackage.setName("m");
		ePackage.setNsPrefix("m");
		ePackage.setNsURI("http://example.org/m");
		EClass c = EcoreFactory.eINSTANCE.createEClass();
		c.setName("Thing");
		ePackage.getEClassifiers().add(c);
		return ePackage;
	}

	@Test
	void requireDynamicAcceptsDynamicPackage() {
		assertThatCode(() -> EcoreAuthoring.requireDynamic(authored())).doesNotThrowAnyException();
	}

	@Test
	void requireDynamicRejectsInstanceClassNameOnEClass() {
		EPackage ePackage = authored();
		((EClass) ePackage.getEClassifier("Thing")).setInstanceClassName("java.lang.Runtime");
		assertThatThrownBy(() -> EcoreAuthoring.requireDynamic(ePackage))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("dynamic");
	}

	@Test
	void requireDynamicRejectsInstanceTypeNameOnEDataType() {
		EPackage ePackage = authored();
		EDataType dataType = EcoreFactory.eINSTANCE.createEDataType();
		dataType.setName("Money");
		dataType.setInstanceTypeName("java.math.BigDecimal");
		ePackage.getEClassifiers().add(dataType);
		assertThatThrownBy(() -> EcoreAuthoring.requireDynamic(ePackage))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("dynamic");
	}

	@Test
	void putEnforcesObjectCap() {
		Dataset dataset = new Dataset("d", new org.eclipse.emf.ecore.resource.impl.ResourceSetImpl(), null);
		DatasetLimits tight = new DatasetLimits(16, 1, 100, 100, 1024, 1024, 60_000L);
		EcoreAuthoring.put(dataset, EcoreFactory.eINSTANCE.createEPackage(), tight);
		assertThatThrownBy(() -> EcoreAuthoring.put(dataset, EcoreFactory.eINSTANCE.createEClass(), tight))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("limit");
	}

	@Test
	void putAssignsSequentialIds() {
		Dataset dataset = new Dataset("d", new org.eclipse.emf.ecore.resource.impl.ResourceSetImpl(), null);
		DatasetLimits limits = DatasetLimits.defaults();
		assertThat(EcoreAuthoring.put(dataset, EcoreFactory.eINSTANCE.createEPackage(), limits)).isEqualTo("o1");
		assertThat(EcoreAuthoring.put(dataset, EcoreFactory.eINSTANCE.createEClass(), limits)).isEqualTo("o2");
	}
}
