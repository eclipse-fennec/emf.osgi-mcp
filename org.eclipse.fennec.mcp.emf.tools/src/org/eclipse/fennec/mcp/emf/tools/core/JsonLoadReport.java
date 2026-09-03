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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a JSON payload actually contributed to the model it was loaded into.
 * <p>
 * The codec drops a key that matches no structural feature without saying so,
 * and {@code objectCount} cannot stand in for the missing signal: a payload
 * whose twelve leaf attributes all vanished still produces exactly the expected
 * number of objects. For an agent inferring a metamodel from samples this is the
 * decisive failure mode — a dropped key means the authored model lacks a feature
 * the payload carries — so it is reported instead of being left for the agent to
 * find by reading the serialization back.
 *
 * @param matchedKeys      JSON keys that resolved to a structural feature
 * @param unmatchedPaths   paths of keys that resolved to no feature at all: the
 *                         model is narrower than the payload
 * @param droppedPaths     paths of keys that did resolve to a feature which is
 *                         nevertheless empty after the load, i.e. the value was
 *                         not accepted; best-effort, see {@link JsonCoverage}
 * @param unsetFeatures    {@code Class.feature} entries no key mentioned: the
 *                         model is wider than the payload. A hint, not a defect
 * @param codecDiagnostics messages the codec itself reported for the load
 * @author ilenia
 * @since Sep 3, 2026
 */
public record JsonLoadReport(int matchedKeys, List<String> unmatchedPaths, List<String> droppedPaths,
		List<String> unsetFeatures, List<String> codecDiagnostics) {

	/**
	 * Cap on every reported list. A pathological payload can produce thousands of
	 * unmatched paths, which would spend exactly the context budget this report
	 * exists to protect; the counts stay exact.
	 */
	static final int MAX_REPORTED = 50;

	/**
	 * @return {@code true} if every key of the payload landed on a feature that
	 *         holds a value and the codec reported nothing
	 */
	public boolean isComplete() {
		return unmatchedPaths.isEmpty() && droppedPaths.isEmpty() && codecDiagnostics.isEmpty();
	}

	/**
	 * @return the report as an ordered map for the tool result. Lists are
	 *         truncated to {@link #MAX_REPORTED} while the counts remain exact,
	 *         and an empty list is omitted rather than reported as {@code []}, so
	 *         a clean load costs the agent almost nothing to read.
	 */
	public Map<String, Object> toMap() {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("complete", isComplete());
		result.put("matchedKeys", matchedKeys);
		result.put("unmatchedCount", unmatchedPaths.size());
		putIfAny(result, "unmatchedPaths", unmatchedPaths);
		if (!droppedPaths.isEmpty()) {
			result.put("droppedCount", droppedPaths.size());
			putIfAny(result, "droppedPaths", droppedPaths);
		}
		if (!unsetFeatures.isEmpty()) {
			result.put("unsetFeatureCount", unsetFeatures.size());
			putIfAny(result, "unsetFeatures", unsetFeatures);
		}
		putIfAny(result, "codecDiagnostics", codecDiagnostics);
		if (isTruncated()) {
			result.put("truncated", Boolean.TRUE);
		}
		return result;
	}

	/**
	 * A one-line summary for a {@code strict} refusal or a server-side log, where
	 * the full map would be noise.
	 *
	 * @return a human-readable summary of the unmatched paths
	 */
	public String describeUnmatched() {
		return String.format("%d of %d payload keys matched no feature: %s", unmatchedPaths.size(),
				unmatchedPaths.size() + matchedKeys, String.join(", ", capped(unmatchedPaths)));
	}

	private boolean isTruncated() {
		return unmatchedPaths.size() > MAX_REPORTED || droppedPaths.size() > MAX_REPORTED
				|| unsetFeatures.size() > MAX_REPORTED || codecDiagnostics.size() > MAX_REPORTED;
	}

	private static void putIfAny(Map<String, Object> result, String key, List<String> values) {
		if (!values.isEmpty()) {
			result.put(key, capped(values));
		}
	}

	private static List<String> capped(List<String> values) {
		return values.size() <= MAX_REPORTED ? List.copyOf(values) : List.copyOf(values.subList(0, MAX_REPORTED));
	}
}
