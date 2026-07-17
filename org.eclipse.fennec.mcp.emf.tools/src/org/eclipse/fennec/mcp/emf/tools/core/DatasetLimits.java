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

/**
 * Hard resource limits guarding the dataset tools against single-request
 * exhaustion (one oversized call can otherwise OOM the runtime regardless of
 * any gateway rate-limiting).
 *
 * @param maxDatasetsPerSession maximum number of datasets per MCP session
 * @param maxObjectsPerDataset  maximum number of objects in one dataset
 * @param maxRecipeOps          maximum number of recorded/replayed recipe operations
 * @param maxValueChars         maximum character length of a single feature value
 * @param maxJsonPayloadBytes   maximum byte size of a declarative JSON payload
 * @param maxInlineExportBytes  maximum byte size returned inline by an export
 * @param sessionTtlMillis      idle time after which a session store is evicted
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
public record DatasetLimits(
		int maxDatasetsPerSession,
		int maxObjectsPerDataset,
		int maxRecipeOps,
		int maxValueChars,
		int maxJsonPayloadBytes,
		int maxInlineExportBytes,
		long sessionTtlMillis) {

	/**
	 * @return conservative defaults matching the {@link DatasetRegistryConfig} defaults
	 */
	public static DatasetLimits defaults() {
		return new DatasetLimits(16, 10_000, 100_000, 65_536, 1_048_576, 65_536, 7_200_000L);
	}
}
