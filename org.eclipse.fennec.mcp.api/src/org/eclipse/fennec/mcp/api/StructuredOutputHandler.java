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

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.osgi.annotation.versioning.ProviderType;
import org.osgi.util.promise.Promise;

/**
 * Generic handler for MCP structured tool output backed by EMF EObjects.
 * Provides synchronous and asynchronous conversion of tool output maps,
 * as well as CRUD-like operations for persisted EMF objects.
 *
 * @param <T> the EMF EObject type this handler manages
 * @author ilenia
 * @since Dec 3, 2025
 */
@ProviderType
public interface StructuredOutputHandler<T extends EObject> {

	/**
	 * Converts a structured output map (from MCP tool result) into a string representation.
	 * @param outputMap the key-value map returned by a tool execution
	 * @return the string representation of the processed output
	 */
	String handleStructuredOutput(Map<String, Object> outputMap);

	/**
	 * Asynchronous variant of {@link #handleStructuredOutput(Map)}.
	 * @param outputMap the key-value map returned by a tool execution
	 * @return a promise resolving to the string representation
	 */
	Promise<String> handleStructuredOutputAsync(Map<String, Object> outputMap);

	/**
	 * Retrieves a previously stored EObject by its identifier.
	 * @param id the unique identifier of the EObject
	 * @return the EObject, or {@code null} if not found
	 * @throws IOException if the storage backend is unavailable
	 */
	T retrieveEObject(String id) throws IOException;

	/**
	 * Lists all EObjects managed by this handler.
	 * @return list of all stored EObjects
	 * @throws IOException if the storage backend is unavailable
	 */
	List<T> listEObjects() throws IOException;

	/**
	 * Deletes a stored EObject by its identifier.
	 * @param id the unique identifier of the EObject to delete
	 * @throws IOException if the storage backend is unavailable
	 */
	void deleteEObject(String id) throws IOException;

}
