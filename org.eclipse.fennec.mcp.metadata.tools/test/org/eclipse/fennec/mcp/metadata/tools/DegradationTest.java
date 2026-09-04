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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.eclipse.fennec.emf.osgi.metadata.MetadataService;
import org.eclipse.fennec.emf.osgi.model.metadata.MetadataFactory;
import org.eclipse.fennec.mcp.api.AnnotationVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The index is bound optionally and dynamically, so it can be absent. Every
 * lookup must then say so rather than throw, and rather than answer "nothing
 * matched" - the two are indistinguishable from the agent's side, and one of
 * them sends it looking for a model that is in fact right there.
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
class DegradationTest {

	private MetadataService metadata;

	@BeforeEach
	void setUp() {
		metadata = mock(MetadataService.class);
		when(metadata.getIndexReader()).thenReturn(Optional.empty());
		when(metadata.getRegistry()).thenReturn(MetadataFactory.eINSTANCE.createMetadataRegistry());
	}

	@Test
	void findClassesByAnnotationReportsTheMissingIndex() {
		FindClassesByAnnotationTool tool = new FindClassesByAnnotationTool();
		tool.metadata = metadata;
		tool.visibility = AnnotationVisibility.unrestricted();
		tool.visibility = AnnotationVisibility.unrestricted();
		tool.activate();

		String error = ToolCalls.callExpectingError(tool, Map.of("annotationSource", "urn:x", "key", "k"));
		assertThat(error)
				.contains("No metadata index is available")
				.contains("describe_metadata_status");
	}

	@Test
	void findFeaturesByAnnotationReportsTheMissingIndex() {
		FindFeaturesByAnnotationTool tool = new FindFeaturesByAnnotationTool();
		tool.metadata = metadata;
		tool.visibility = AnnotationVisibility.unrestricted();
		tool.visibility = AnnotationVisibility.unrestricted();
		tool.activate();

		assertThat(ToolCalls.callExpectingError(tool, Map.of("annotationSource", "urn:x", "key", "k")))
				.contains("No metadata index is available");
	}

	@Test
	void findOperationsByAnnotationReportsTheMissingIndex() {
		FindOperationsByAnnotationTool tool = new FindOperationsByAnnotationTool();
		tool.metadata = metadata;
		tool.visibility = AnnotationVisibility.unrestricted();
		tool.visibility = AnnotationVisibility.unrestricted();
		tool.activate();

		assertThat(ToolCalls.callExpectingError(tool, Map.of("annotationSource", "urn:x", "key", "k")))
				.contains("No metadata index is available");
	}

	@Test
	void findClassByNameReportsTheMissingIndex() {
		FindClassByNameTool tool = new FindClassByNameTool();
		tool.metadata = metadata;
		tool.activate();

		assertThat(ToolCalls.callExpectingError(tool, Map.of("className", "Gateway")))
				.contains("No metadata index is available");
	}

	@Test
	void describeAspectsReportsTheMissingIndexForAClass() {
		DescribeAspectsTool tool = new DescribeAspectsTool();
		tool.metadata = metadata;
		tool.visibility = AnnotationVisibility.unrestricted();
		tool.visibility = AnnotationVisibility.unrestricted();
		tool.activate();

		assertThat(ToolCalls.callExpectingError(tool, Map.of("element", "urn:x#//Thing")))
				.contains("No metadata index is available");
	}

	@Test
	void statusStillAnswersAndSaysWhyEverythingElseWillNot() {
		DescribeMetadataStatusTool tool = new DescribeMetadataStatusTool();
		tool.metadata = metadata;
		tool.visibility = AnnotationVisibility.unrestricted();
		tool.visibility = AnnotationVisibility.unrestricted();
		tool.activate();

		Map<String, Object> result = ToolCalls.call(tool, Map.of());
		assertThat(result)
				.containsEntry("metadataServiceAvailable", Boolean.TRUE)
				.containsEntry("indexAvailable", Boolean.FALSE)
				.containsEntry("registeredPackageVersions", 0);
		assertThat(String.valueOf(result.get("note"))).contains("No metadata index is bound");
	}
}
