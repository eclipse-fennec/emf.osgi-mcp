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
import java.util.Map;

/**
 * One operation of a dataset build recipe. A recipe is the ordered list of all
 * mutating operations applied to a dataset; replaying it deterministically
 * reproduces the identical dataset (and therefore byte-identical XMI) without
 * involving an LLM. Object ids inside a recipe are dataset-local
 * ({@code o1, o2, ...}), which keeps recipes portable across sessions.
 *
 * @param op       the operation: {@code create}, {@code fromJson}, {@code set},
 *                 {@code unset}, {@code add}, {@code remove} or {@code delete}
 * @param objectId the dataset-local id of the target object
 * @param eClass   the class identifier for {@code create}/{@code fromJson}
 * @param feature  the structural feature name for feature operations
 * @param value    the attribute value or declarative JSON payload
 * @param ref      the reference target: a dataset-local object id ({@code o<N>})
 *                 or a class-reference identifier ({@code <nsURI>#//<Name>}) for
 *                 a registry/built-in classifier; the two are disambiguated by
 *                 the {@code #//} separator, which a dataset-local id never contains
 * @param index    the optional list index for {@code add}/{@code remove}
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
public record RecipeOp(String op, String objectId, String eClass, String feature, Object value, String ref, Integer index) {

	public static final String OP_CREATE = "create";
	public static final String OP_FROM_JSON = "fromJson";
	public static final String OP_SET = "set";
	public static final String OP_UNSET = "unset";
	public static final String OP_ADD = "add";
	public static final String OP_REMOVE = "remove";
	public static final String OP_DELETE = "delete";

	public static RecipeOp create(String objectId, String eClass) {
		return new RecipeOp(OP_CREATE, objectId, eClass, null, null, null, null);
	}

	public static RecipeOp fromJson(String objectId, String eClass, Map<String, Object> data) {
		return new RecipeOp(OP_FROM_JSON, objectId, eClass, null, data, null, null);
	}

	public static RecipeOp set(String objectId, String feature, Object value, String ref) {
		return new RecipeOp(OP_SET, objectId, null, feature, value, ref, null);
	}

	public static RecipeOp unset(String objectId, String feature) {
		return new RecipeOp(OP_UNSET, objectId, null, feature, null, null, null);
	}

	public static RecipeOp add(String objectId, String feature, Object value, String ref, Integer index) {
		return new RecipeOp(OP_ADD, objectId, null, feature, value, ref, index);
	}

	public static RecipeOp remove(String objectId, String feature, Object value, String ref, Integer index) {
		return new RecipeOp(OP_REMOVE, objectId, null, feature, value, ref, index);
	}

	public static RecipeOp delete(String objectId) {
		return new RecipeOp(OP_DELETE, objectId, null, null, null, null, null);
	}

	/**
	 * @return a JSON-friendly map representation omitting unset parts
	 */
	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("op", op);
		map.put("objectId", objectId);
		if (eClass != null) {
			map.put("eClass", eClass);
		}
		if (feature != null) {
			map.put("feature", feature);
		}
		if (value != null) {
			map.put("value", value);
		}
		if (ref != null) {
			map.put("ref", ref);
		}
		if (index != null) {
			map.put("index", index);
		}
		return map;
	}

	/**
	 * Parses a recipe operation from its map representation.
	 *
	 * @param map the map as produced by {@link #toMap()}
	 * @return the parsed operation
	 * @throws ToolException if mandatory parts are missing or malformed
	 */
	@SuppressWarnings("unchecked")
	public static RecipeOp fromMap(Map<String, Object> map) {
		Object op = map.get("op");
		Object objectId = map.get("objectId");
		if (!(op instanceof String opString) || opString.isBlank()) {
			throw new ToolException("Recipe operation requires an 'op' string");
		}
		if (!(objectId instanceof String idString) || idString.isBlank()) {
			throw new ToolException(String.format("Recipe operation '%s' requires an 'objectId'", opString));
		}
		Object value = map.get("value");
		if (OP_FROM_JSON.equals(opString) && !(value instanceof Map)) {
			throw new ToolException("Recipe operation 'fromJson' requires a 'value' object");
		}
		Object index = map.get("index");
		return new RecipeOp(opString, idString,
				stringOrNull(map.get("eClass")),
				stringOrNull(map.get("feature")),
				value instanceof Map ? (Map<String, Object>) value : value,
				stringOrNull(map.get("ref")),
				index instanceof Number number ? number.intValue() : null);
	}

	private static String stringOrNull(Object value) {
		return value instanceof String string && !string.isBlank() ? string : null;
	}
}
