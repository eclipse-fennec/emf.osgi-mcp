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

import java.lang.annotation.Annotation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The deployed annotation visibility policy.
 * <p>
 * The behaviour that matters most here is the <em>unconfigured</em> one: this is
 * a deny-list, so absent configuration must permit everything, and the component
 * must still exist. A component that required configuration would take the
 * annotation tools down with it wherever the PID is not deployed.
 *
 * @author ilenia
 * @since Sep 3, 2026
 */
class AnnotationVisibilityComponentTest {

	private static final String CODEC_SOURCE = "http://eclipse.org/fennec/codec/typeMapping/uplink";
	private static final String EMD_SOURCE = "http:///org/eclipse/emf/ecore/util/ExtendedMetaData";

	@Test
	@DisplayName("unconfigured denies nothing")
	void unconfiguredPermitsEverything() {
		AnnotationVisibility visibility = visibility(new String[0], new String[0]);

		assertThat(visibility.isUnrestricted()).isTrue();
		assertThat(visibility.isSourceVisible(CODEC_SOURCE)).isTrue();
		assertThat(visibility.isAspectTypeVisible("codec")).isTrue();
	}

	@Test
	@DisplayName("an exact source entry denies that source and no other")
	void exactSourceIsDenied() {
		AnnotationVisibility visibility = visibility(new String[] { CODEC_SOURCE }, new String[0]);

		assertThat(visibility.isSourceVisible(CODEC_SOURCE)).isFalse();
		assertThat(visibility.isSourceVisible(EMD_SOURCE)).isTrue();
		assertThat(visibility.isUnrestricted()).isFalse();
	}

	@Test
	@DisplayName("a prefix entry denies the subtree, anchored on the whole string")
	void prefixDeniesTheSubtree() {
		AnnotationVisibility visibility = visibility(new String[] { "http://eclipse.org/fennec/codec/*" },
				new String[0]);

		assertThat(visibility.isSourceVisible(CODEC_SOURCE)).isFalse();
		assertThat(visibility.isSourceVisible("http://eclipse.org/fennec/other/x")).isTrue();
		// not a substring search: a hostile source cannot smuggle the prefix inside
		assertThat(visibility.isSourceVisible("http://evil.example/http://eclipse.org/fennec/codec/x")).isTrue();
	}

	@Test
	@DisplayName("a bare wildcard denies every source")
	void wildcardDeniesEverything() {
		AnnotationVisibility visibility = visibility(new String[] { "*" }, new String[0]);

		assertThat(visibility.isSourceVisible(CODEC_SOURCE)).isFalse();
		assertThat(visibility.isSourceVisible(EMD_SOURCE)).isFalse();
	}

	@Test
	@DisplayName("a null source stays visible: it carries no identity a rule could name")
	void nullSourceIsVisible() {
		assertThat(visibility(new String[] { "*" }, new String[0]).isSourceVisible(null)).isTrue();
	}

	@Test
	@DisplayName("the aspect list is independent of the source list")
	void theTwoListsAreIndependent() {
		AnnotationVisibility sourceOnly = visibility(new String[] { CODEC_SOURCE }, new String[0]);
		AnnotationVisibility aspectOnly = visibility(new String[0], new String[] { "codec" });

		// This independence is the trap the config comment warns about: denying the
		// source does NOT hide the aspect parsed from it, because an AspectEntry
		// carries a type id and no source.
		assertThat(sourceOnly.isAspectTypeVisible("codec")).isTrue();
		assertThat(aspectOnly.isSourceVisible(CODEC_SOURCE)).isTrue();
		assertThat(aspectOnly.isAspectTypeVisible("codec")).isFalse();
	}

	@Test
	@DisplayName("a duplicated entry does not stop the policy from activating")
	void duplicateEntriesAreTolerated() {
		// Set.of would throw here, which would leave a runtime with no policy at all
		// because of a repeated line in a hand-edited list.
		AnnotationVisibility visibility = visibility(new String[] { CODEC_SOURCE, CODEC_SOURCE }, new String[0]);

		assertThat(visibility.isSourceVisible(CODEC_SOURCE)).isFalse();
	}

	@Test
	@DisplayName("a blank entry is a configuration slip, not a wildcard")
	void blankEntriesDenyNothing() {
		AnnotationVisibility visibility = visibility(new String[] { "", "   " }, new String[] { "" });

		assertThat(visibility.isSourceVisible(CODEC_SOURCE)).isTrue();
		assertThat(visibility.isAspectTypeVisible("codec")).isTrue();
	}

	@Test
	@DisplayName("a reconfiguration takes effect without a restart")
	void updateReplacesThePolicy() {
		AnnotationVisibilityComponent component = new AnnotationVisibilityComponent();
		component.activate(config(new String[0], new String[0]));
		assertThat(component.isSourceVisible(CODEC_SOURCE)).isTrue();

		component.update(config(new String[] { CODEC_SOURCE }, new String[0]));

		assertThat(component.isSourceVisible(CODEC_SOURCE)).isFalse();
	}

	@Test
	@DisplayName("the unrestricted implementation denies nothing")
	void theUnrestrictedFallbackPermitsEverything() {
		AnnotationVisibility visibility = AnnotationVisibility.unrestricted();

		assertThat(visibility.isUnrestricted()).isTrue();
		assertThat(visibility.isSourceVisible(CODEC_SOURCE)).isTrue();
		assertThat(visibility.isAspectTypeVisible("codec")).isTrue();
	}

	private static AnnotationVisibility visibility(String[] sources, String[] aspectTypes) {
		AnnotationVisibilityComponent component = new AnnotationVisibilityComponent();
		component.activate(config(sources, aspectTypes));
		return component;
	}

	private static AnnotationVisibilityConfig config(String[] sources, String[] aspectTypes) {
		return new AnnotationVisibilityConfig() {

			@Override
			public String[] annotation_source_denylist() {
				return sources;
			}

			@Override
			public String[] aspect_type_denylist() {
				return aspectTypes;
			}

			@Override
			public Class<? extends Annotation> annotationType() {
				// An @interface implicitly extends Annotation, so a hand-built config
				// has to answer this too.
				return AnnotationVisibilityConfig.class;
			}
		};
	}
}
