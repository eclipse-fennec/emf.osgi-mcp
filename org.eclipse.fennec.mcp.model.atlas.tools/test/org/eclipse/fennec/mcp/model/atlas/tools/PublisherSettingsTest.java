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
package org.eclipse.fennec.mcp.model.atlas.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The publishing policy: what may be published, and where it goes.
 *
 * @author ilenia
 * @since Aug 27, 2026
 */
class PublisherSettingsTest {

	private static PublisherSettings settings(List<String> allowList) {
		return new PublisherSettings("jena", "draft", "schema", "application/xmi", false, allowList);
	}

	@Test
	void anEmptyAllowListPublishesNothing() {
		PublisherSettings settings = settings(List.of());

		assertThat(settings.isPublishable(TestModels.DERIVED_NS_URI)).isFalse();
		assertThat(settings.isPublishable("")).isFalse();
		assertThat(settings.isPublishable(null)).isFalse();
	}

	@Test
	void anExactRuleAdmitsOnlyThatNamespace() {
		PublisherSettings settings = settings(List.of(TestModels.DERIVED_NS_URI));

		assertThat(settings.isPublishable(TestModels.DERIVED_NS_URI)).isTrue();
		assertThat(settings.isPublishable(TestModels.DERIVED_NS_URI + "/2.0")).isFalse();
		assertThat(settings.isPublishable(TestModels.BASE_NS_URI)).isFalse();
	}

	@Test
	void aPrefixRuleIsAnchoredOnTheWholeUri() {
		PublisherSettings settings = settings(List.of("https://eclipse.org/fennec/test/inference/*"));

		assertThat(settings.isPublishable(TestModels.DERIVED_NS_URI)).isTrue();
		// The rule must not admit a namespace that merely contains it: a host swap is
		// exactly the case a substring match would let through.
		assertThat(settings.isPublishable("https://evil.example/https://eclipse.org/fennec/test/inference/x"))
				.isFalse();
	}

	@Test
	void blankRulesAreIgnoredRatherThanMatchingEverything() {
		PublisherSettings settings = settings(List.of("", "   "));

		assertThat(settings.isPublishable(TestModels.DERIVED_NS_URI)).isFalse();
	}

	@Test
	void theAllowListCannotBeMutatedThroughTheListItWasBuiltFrom() {
		List<String> mutable = new java.util.ArrayList<>();
		mutable.add(TestModels.DERIVED_NS_URI);
		PublisherSettings settings = settings(mutable);
		mutable.add(TestModels.BASE_NS_URI);

		assertThat(settings.isPublishable(TestModels.BASE_NS_URI)).isFalse();
	}

	@Test
	void thePathsMatchTheSchemaPackagesResource() {
		PublisherSettings settings = settings(List.of());

		// SchemaPackagesResource is @Path("/{scopeName}/schema") with the create method
		// at @Path("/stages/{stageName}").
		assertThat(settings.createPackagePath()).isEqualTo("jena/schema/stages/draft");
		assertThat(settings.stagePath()).isEqualTo("jena/schema/stages/draft");
	}
}
