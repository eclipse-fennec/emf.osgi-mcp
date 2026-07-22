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

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.junit.jupiter.api.Test;

/**
 * Tests the hardened inline-XMI loader {@link XmiImport}.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
class XmiImportTest {

	private static final String ECORE = """
			<?xml version="1.0" encoding="UTF-8"?>
			<ecore:EPackage xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI"
					xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
					xmlns:ecore="http://www.eclipse.org/emf/2002/Ecore"
					name="lib" nsURI="http://example.org/lib" nsPrefix="lib">
				<eClassifiers xsi:type="ecore:EClass" name="Book">
					<eStructuralFeatures xsi:type="ecore:EAttribute" name="title"
						eType="ecore:EDataType http://www.eclipse.org/emf/2002/Ecore#//EString"/>
				</eClassifiers>
			</ecore:EPackage>
			""";

	@Test
	void loadsSelfContainedEcore() {
		List<EObject> roots = XmiImport.loadDetached(ECORE, 1_000_000, List.of());
		assertThat(roots).hasSize(1);
		EPackage ePackage = (EPackage) roots.get(0);
		assertThat(ePackage.getNsURI()).isEqualTo("http://example.org/lib");
		assertThat(ePackage.getEClassifier("Book")).isNotNull();
		// detached from the transient load resource
		assertThat(ePackage.eResource()).isNull();
	}

	@Test
	void rejectsDoctype() {
		String withDoctype = "<?xml version=\"1.0\"?>\n<!DOCTYPE foo [ <!ENTITY x \"y\"> ]>\n" + ECORE;
		assertThatThrownBy(() -> XmiImport.loadDetached(withDoctype, 1_000_000, List.of()))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("DOCTYPE");
	}

	@Test
	void rejectsOversizedDocument() {
		assertThatThrownBy(() -> XmiImport.loadDetached(ECORE, 32, List.of()))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("limit");
	}

	@Test
	void rejectsReferenceToUnavailablePackage() {
		// an instance of a package that is not provided as available -> not resolvable, rejected
		String instance = """
				<?xml version="1.0" encoding="UTF-8"?>
				<lib:Book xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI"
						xmlns:lib="http://example.org/lib" title="Dune"/>
				""";
		assertThatThrownBy(() -> XmiImport.loadDetached(instance, 1_000_000, List.of()))
				.isInstanceOf(ToolException.class);
	}

	@Test
	void rejectsBlankInput() {
		assertThatThrownBy(() -> XmiImport.loadDetached("  ", 1_000_000, List.of()))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("required");
	}
}
