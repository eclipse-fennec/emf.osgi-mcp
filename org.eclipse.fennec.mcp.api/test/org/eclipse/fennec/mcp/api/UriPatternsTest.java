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
package org.eclipse.fennec.mcp.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The pattern language shared by the guard's allow-lists and the package
 * registry's registration policy.
 *
 * @author ilenia
 * @since Aug 27, 2026
 */
class UriPatternsTest {

	private static final String NS_URI = "http://example.org/library";

	@Test
	void anEmptyListMatchesNothing() {
		assertThat(UriPatterns.matches(List.of(), NS_URI)).isFalse();
	}

	@Test
	void anExactEntryMatchesOnlyItself() {
		List<String> patterns = List.of(NS_URI);

		assertThat(UriPatterns.matches(patterns, NS_URI)).isTrue();
		assertThat(UriPatterns.matches(patterns, NS_URI + "/2.0")).isFalse();
		assertThat(UriPatterns.matches(patterns, "http://example.org")).isFalse();
	}

	@Test
	void aPrefixEntryMatchesEveryExtension() {
		List<String> patterns = List.of("http://example.org/*");

		assertThat(UriPatterns.matches(patterns, NS_URI)).isTrue();
		assertThat(UriPatterns.matches(patterns, "http://example.org/other")).isTrue();
		assertThat(UriPatterns.matches(patterns, "http://example.com/library")).isFalse();
	}

	@Test
	void aPrefixEntryIsNotASubstringSearch() {
		// A host swap must not satisfy a rule about a namespace root.
		assertThat(UriPatterns.matches(List.of("http://example.org/*"),
				"http://evil.example/http://example.org/library")).isFalse();
	}

	@Test
	void theBareWildcardMatchesEverything() {
		assertThat(UriPatterns.matches(List.of("*"), NS_URI)).isTrue();
		assertThat(UriPatterns.matches(List.of("*"), "")).isTrue();
	}

	@Test
	void aClassIdentifierPatternNarrowsToOnePackage() {
		List<String> patterns = List.of(NS_URI + "#//*");

		assertThat(UriPatterns.matches(patterns, NS_URI + "#//Book")).isTrue();
		assertThat(UriPatterns.matches(patterns, "http://other.org/x#//Book")).isFalse();
	}

	@Test
	void aBlankEntryIsIgnoredRatherThanTreatedAsAWildcard() {
		// Otherwise a stray line in a hand-edited list turns deny-all into allow-all.
		assertThat(UriPatterns.matches(Arrays.asList("", "   ", null), NS_URI)).isFalse();
	}

	@Test
	void aNullValueNeverMatches() {
		assertThat(UriPatterns.matches(List.of("*"), null)).isFalse();
	}
}
