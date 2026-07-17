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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.ResourceSet;

/**
 * A session-scoped set of {@link EObject}s under construction, together with
 * the build recipe that reproduces it. The dataset is the unit of state of the
 * EMF model MCP tools: objects are addressed by a dataset-local, deterministic
 * id ({@code o1, o2, ...} — generated because target classes cannot be assumed
 * to have ID features), and every mutation is recorded as a {@link RecipeOp}.
 * <p>
 * All object/recipe access is synchronized on the dataset instance; tool
 * executions run on a multi-threaded reactive scheduler.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
public final class Dataset {

	private final String id;
	private final ResourceSet resourceSet;
	private final Long seed;
	private final long createdAt;
	private volatile long lastAccess;

	private final Map<String, EObject> objects = new LinkedHashMap<>();
	private final List<RecipeOp> recipe = new ArrayList<>();
	private long idCounter = 0;

	Dataset(String id, ResourceSet resourceSet, Long seed) {
		this.id = id;
		this.resourceSet = resourceSet;
		this.seed = seed;
		this.createdAt = System.currentTimeMillis();
		this.lastAccess = createdAt;
	}

	public String getId() {
		return id;
	}

	public ResourceSet getResourceSet() {
		return resourceSet;
	}

	public Long getSeed() {
		return seed;
	}

	public long getCreatedAt() {
		return createdAt;
	}

	long getLastAccess() {
		return lastAccess;
	}

	void touch() {
		lastAccess = System.currentTimeMillis();
	}

	/**
	 * @return the next deterministic dataset-local object id
	 */
	public synchronized String nextObjectId() {
		return "o" + (++idCounter);
	}

	/**
	 * Registers an object under the given id.
	 */
	public synchronized void putObject(String objectId, EObject eObject) {
		objects.put(objectId, eObject);
		syncCounter(objectId);
	}

	/**
	 * @return the object with the given id
	 * @throws ToolException if the id is unknown in this dataset
	 */
	public synchronized EObject requireObject(String objectId) {
		EObject eObject = objects.get(objectId);
		if (eObject == null) {
			throw new ToolException(String.format("Unknown objectId '%s' in dataset '%s'. Use inspect_dataset to see the existing objects.", objectId, id));
		}
		return eObject;
	}

	public synchronized EObject removeObject(String objectId) {
		return objects.remove(objectId);
	}

	public synchronized int objectCount() {
		return objects.size();
	}

	/**
	 * @return a snapshot of the current id-to-object entries, in insertion order
	 */
	public synchronized Map<String, EObject> objectsSnapshot() {
		return new LinkedHashMap<>(objects);
	}

	/**
	 * @return a snapshot of all root objects (objects without a container), in insertion order
	 */
	public synchronized List<EObject> roots() {
		return objects.values().stream().filter(o -> o.eContainer() == null).toList();
	}

	/**
	 * Reverse-looks-up the dataset-local id of an object.
	 * @return the id, or {@code null} if the object is not registered
	 */
	public synchronized String idOf(EObject eObject) {
		for (Map.Entry<String, EObject> entry : objects.entrySet()) {
			if (entry.getValue() == eObject) {
				return entry.getKey();
			}
		}
		return null;
	}

	/**
	 * Records a recipe operation.
	 */
	public synchronized void record(RecipeOp op) {
		recipe.add(op);
	}

	public synchronized int recipeSize() {
		return recipe.size();
	}

	/**
	 * @return a snapshot of the recorded recipe
	 */
	public synchronized List<RecipeOp> recipeSnapshot() {
		return List.copyOf(recipe);
	}

	/**
	 * Drops all objects and resets the id counter, keeping the recipe.
	 * Used by the deterministic regenerate-replay.
	 */
	public synchronized void clearObjects() {
		objects.clear();
		resourceSet.getResources().clear();
		idCounter = 0;
	}

	/**
	 * Drops all objects and the recipe (action {@code clear}).
	 */
	public synchronized void reset() {
		clearObjects();
		recipe.clear();
	}

	private void syncCounter(String objectId) {
		if (objectId.length() > 1 && objectId.charAt(0) == 'o') {
			try {
				idCounter = Math.max(idCounter, Long.parseLong(objectId.substring(1)));
			} catch (NumberFormatException e) {
				// foreign id format — counter stays untouched
			}
		}
	}
}
