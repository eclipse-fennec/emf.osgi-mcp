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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.fennec.mcp.api.StructuredOutputStorageHelper;

import tools.jackson.databind.json.JsonMapper;

/**
 * Declarative JSON-to-EObject loading shared by {@code create_from_json},
 * the regenerate replay and {@code replay_recipe}. Deserialization goes
 * through the Fennec codec ({@link StructuredOutputStorageHelper}); the
 * resulting containment tree is registered in the dataset with deterministic
 * object ids (root first, children in containment-iteration order), so a
 * replayed {@code fromJson} operation reproduces identical ids.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
public final class FromJsonSupport {

	private static final Logger LOGGER = Logger.getLogger(FromJsonSupport.class.getName());

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private FromJsonSupport() {
	}

	/**
	 * Loads a JSON payload as instance of the given class into the dataset.
	 *
	 * @param dataset  the target dataset
	 * @param objectId the dataset-local id for the root object
	 * @param eClass   the (allow-listed) root class
	 * @param data     the JSON payload as map
	 * @param limits   the resource limits to enforce
	 * @return what the payload contributed to the loaded graph — the caller is
	 *         free to ignore it, but {@code create_from_json} reports it, because
	 *         a key the codec dropped is invisible in the object count
	 * @throws ToolException if the payload is too large, the object cap is hit
	 *                       or the payload cannot be deserialized
	 */
	public static JsonLoadReport load(Dataset dataset, String objectId, EClass eClass, Map<String, Object> data, DatasetLimits limits) {
		byte[] payload;
		try {
			payload = MAPPER.writeValueAsBytes(data);
		} catch (RuntimeException e) {
			throw new ToolException("Parameter 'data' is not serializable JSON");
		}
		if (payload.length > limits.maxJsonPayloadBytes()) {
			throw new ToolException(String.format("JSON payload of %d bytes exceeds the limit of %d bytes",
					payload.length, limits.maxJsonPayloadBytes()));
		}
		List<String> diagnostics = new ArrayList<>();
		EObject root = StructuredOutputStorageHelper.loadEObject(eClass, data, dataset.getResourceSet(), diagnostics);
		if (root == null) {
			throw new ToolException(String.format(
					"Could not deserialize the JSON payload into a '%s'. Check the structure with describe_eclass; "
							+ "this operation also requires the Fennec codec to be installed.%s", eClass.getName(),
					diagnostics.isEmpty() ? "" : " The codec reported: " + String.join("; ", diagnostics)));
		}
		List<EObject> children = new ArrayList<>();
		root.eAllContents().forEachRemaining(children::add);
		// Cap check and the whole tree insert must be atomic against concurrent
		// calls on the same dataset (Dataset locks on itself); otherwise two
		// parallel loads could each pass the check and blow past the object cap.
		synchronized (dataset) {
			if (dataset.objectCount() + 1 + children.size() > limits.maxObjectsPerDataset()) {
				throw new ToolException(String.format("Dataset object limit of %d reached", limits.maxObjectsPerDataset()));
			}
			dataset.putObject(objectId, root);
			for (EObject child : children) {
				dataset.putObject(dataset.nextObjectId(), child);
			}
		}
		// After the insert, so a report is only ever produced for a graph that is
		// actually in the dataset; the analysis reads the tree and mutates nothing,
		// so it needs no share of the lock above.
		return JsonCoverage.analyse(root, data, diagnostics);
	}

	/**
	 * Loads like {@link #load}, logging rather than returning the report.
	 * <p>
	 * For the replay paths ({@code manage_dataset} regenerate and
	 * {@code replay_recipe}), which reload a payload the server itself recorded:
	 * there the agent asked to reproduce a graph, not to check a sample, so
	 * coverage is not part of the answer — but a recorded payload that suddenly
	 * matches fewer features means the metamodel moved under the recipe, and that
	 * belongs in the log rather than nowhere.
	 *
	 * @param dataset  the target dataset
	 * @param objectId the dataset-local id for the root object
	 * @param eClass   the (allow-listed) root class
	 * @param data     the JSON payload as map
	 * @param limits   the resource limits to enforce
	 */
	public static void loadAndWarn(Dataset dataset, String objectId, EClass eClass, Map<String, Object> data,
			DatasetLimits limits) {
		JsonLoadReport report = load(dataset, objectId, eClass, data, limits);
		if (!report.unmatchedPaths().isEmpty()) {
			LOGGER.warning(() -> String.format("Replayed fromJson payload for '%s' in dataset '%s': %s",
					eClass.getName(), dataset.getId(), report.describeUnmatched()));
		}
	}
}
