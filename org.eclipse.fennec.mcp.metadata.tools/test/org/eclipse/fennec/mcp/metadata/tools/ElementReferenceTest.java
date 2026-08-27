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
package org.eclipse.fennec.mcp.metadata.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.eclipse.fennec.mcp.metadata.tools.core.ElementReference;
import org.eclipse.fennec.mcp.metadata.tools.core.ElementReference.Kind;
import org.eclipse.fennec.mcp.metadata.tools.core.ToolException;
import org.junit.jupiter.api.Test;

/**
 * Parsing of the {@code <nsURI>[#//<Class>[/<member>]]} element notation.
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
class ElementReferenceTest {

	@Test
	void aBareNamespaceIsAPackage() {
		ElementReference reference = ElementReference.parse("https://example.org/uplink");

		assertThat(reference.kind()).isEqualTo(Kind.PACKAGE);
		assertThat(reference.nsURI()).isEqualTo("https://example.org/uplink");
		assertThat(reference.reference()).isEqualTo("https://example.org/uplink");
	}

	@Test
	void aFragmentIsAClass() {
		ElementReference reference = ElementReference.parse("https://example.org/uplink#//UplinkMessage");

		assertThat(reference.kind()).isEqualTo(Kind.CLASS);
		assertThat(reference.className()).isEqualTo("UplinkMessage");
		assertThat(reference.memberName()).isNull();
	}

	@Test
	void aTrailingSegmentIsAMember() {
		ElementReference reference = ElementReference.parse("https://example.org/uplink#//UplinkMessage/deviceInfo");

		assertThat(reference.kind()).isEqualTo(Kind.MEMBER);
		assertThat(reference.className()).isEqualTo("UplinkMessage");
		assertThat(reference.memberName()).isEqualTo("deviceInfo");
		assertThat(reference.reference()).isEqualTo("https://example.org/uplink#//UplinkMessage/deviceInfo");
	}

	@Test
	void malformedReferencesAreRejectedWithTheExpectedShapes() {
		assertThatThrownBy(() -> ElementReference.parse("   "))
				.isInstanceOf(ToolException.class)
				.hasMessageContaining("<nsURI>#//<ClassName>");
		assertThatThrownBy(() -> ElementReference.parse("https://example.org/uplink#//"))
				.isInstanceOf(ToolException.class);
		assertThatThrownBy(() -> ElementReference.parse("https://example.org/uplink#//A/b/c"))
				.isInstanceOf(ToolException.class);
	}
}
