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
import static org.eclipse.fennec.mcp.metadata.tools.ToolCalls.call;
import static org.eclipse.fennec.mcp.metadata.tools.ToolCalls.map;
import static org.eclipse.fennec.mcp.metadata.tools.ToolCalls.strings;

import java.util.Map;

import org.eclipse.fennec.codec.metadata.provider.CodecAspectProvider;
import org.eclipse.fennec.emf.osgi.metadata.MetadataServices;
import org.eclipse.fennec.emf.osgi.metadata.MetadataWhiteboard;
import org.eclipse.fennec.mcp.api.AnnotationVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The wiring diagnostics and the per-package view.
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
class StatusToolsTest {

	private DescribeMetadataStatusTool status;
	private DescribePackageMetadataTool describePackage;

	@BeforeEach
	void setUp() {
		MetadataWhiteboard whiteboard = MetadataServices.createWhiteboard(new CodecAspectProvider());
		whiteboard.registerPackage(TestModels.uplinkPackage());
		whiteboard.registerPackage(TestModels.gatewayPackage(), Map.of("service.id", 42L));

		status = new DescribeMetadataStatusTool();
		status.metadata = whiteboard;
		status.visibility = AnnotationVisibility.unrestricted();
		status.activate();
		describePackage = new DescribePackageMetadataTool();
		describePackage.metadata = whiteboard;
		describePackage.activate();
	}

	@Test
	void statusReportsTheIndexAndBothPopulations() {
		Map<String, Object> result = call(status, Map.of());

		assertThat(result)
				.containsEntry("indexAvailable", Boolean.TRUE)
				.containsEntry("registeredPackageVersions", 2)
				.containsEntry("distinctNamespaces", 2);
		assertThat(strings(result, "namespaces"))
				.containsExactly(TestModels.GATEWAY_NS_URI, TestModels.UPLINK_NS_URI);
		assertThat(map(result, "packageVersionsByOrigin"))
				.containsEntry("osgi-service", 1)
				.containsEntry("session", 1);
		assertThat(strings(result, "aspectTypeIds")).contains("codec");
		assertThat(result).doesNotContainKey("note");
	}

	@Test
	void aPackageIsDescribedWithItsClassesAndOrigin() {
		Map<String, Object> result = call(describePackage, Map.of("nsURI", TestModels.UPLINK_NS_URI));

		assertThat(result)
				.containsEntry("name", "uplink")
				.containsEntry("origin", "session")
				.containsEntry("classCount", 4)
				.containsEntry("versionCount", 1);
		assertThat(strings(result, "classes")).contains(
				TestModels.UPLINK_MESSAGE, TestModels.SENSOR_A_UPLINK, TestModels.SENSOR_B_UPLINK);
		assertThat(strings(result, "abstractClasses")).containsExactly(TestModels.UPLINK_MESSAGE);
		assertThat(result.get("modelFingerprint")).isNotNull();
	}

	@Test
	void anOsgiRegisteredPackageIsMarkedAsSuch() {
		Map<String, Object> result = call(describePackage, Map.of("nsURI", TestModels.GATEWAY_NS_URI));

		assertThat(result).containsEntry("origin", "osgi-service");
		assertThat(map(result, "properties")).containsKey("service.id");
	}

	@Test
	void anUnknownNamespaceIsAnActionableError() {
		String error = ToolCalls.callExpectingError(describePackage, Map.of("nsURI", "https://example.org/nope"));
		assertThat(error).contains("describe_metadata_status");
	}
}
