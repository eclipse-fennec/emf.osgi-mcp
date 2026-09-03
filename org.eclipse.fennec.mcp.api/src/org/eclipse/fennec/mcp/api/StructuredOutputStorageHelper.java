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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.fennec.codec.config.ConfigProperty;
import org.eclipse.fennec.codec.resource.CodecResource;

import tools.jackson.databind.ObjectMapper;

/**
 * Utility class for deserializing MCP tool output maps into EMF EObjects.
 * Uses the Fennec codec to load JSON data into typed EMF model instances,
 * enabling structured output handling where tool results map to known Ecore models.
 *
 * @author ilenia
 * @since Jan 8, 2026
 */
public class StructuredOutputStorageHelper {
	
	private static final Logger LOGGER = Logger.getLogger(StructuredOutputStorageHelper.class.getName());

	/**
	 * Synthetic absolute URI for the in-memory JSON load. A relative URI would
	 * resolve against the process working directory and suggest filesystem
	 * semantics; the load only ever reads from the passed stream.
	 */
	private static final URI STRUCTURED_OUTPUT_URI = URI.createURI("mcp://structured-output/temp.json");

	/**
	 * Deserializes a property map into an EMF EObject, using the EClass identified by its URI.
	 * The map is first converted to JSON, then loaded through the Fennec codec resource.
	 *
	 * @param classUri    the EMF URI identifying the target EClass
	 * @param propertyMap the key-value map to deserialize (typically from MCP tool output)
	 * @param resourceSet the resource set capable of resolving the EClass URI and loading JSON
	 * @return the deserialized EObject, or {@code null} if loading fails or types don't match
	 */
	public static EObject loadEObject(String classUri, Map<String, Object> propertyMap, ResourceSet resourceSet)  {
		try {
			String jsonString = mapToJsonString(propertyMap);
			InputStream inputStream = stringToInputStream(jsonString);
			Resource resource = resourceSet.createResource(STRUCTURED_OUTPUT_URI);
			EObject eClassEO = resourceSet.getEObject(URI.createURI(classUri), false);
			Map<String, Object> options = new HashMap<>();
			options.put(CodecResource.CODEC_ROOT_TYPE, eClassEO);
			options.put(ConfigProperty.USE_NAMES_FROM_EXTENDED_METADATA.getKey(), true);
			resource.load(inputStream, options);
			 if(!resource.getContents().isEmpty() && EcoreUtil.getURI(resource.getContents().get(0).eClass()).toString().equals(classUri)) {
				return resource.getContents().get(0);
			}

		} catch (IOException e) {
			LOGGER.severe(String.format("IOException when trying to load structured output into known EObject of EClass %s", classUri));
			e.printStackTrace();
		}
		return null;

	}
	
	/**
	 * Deserializes a property map into an EMF EObject using a direct EClass reference.
	 * Overload of {@link #loadEObject(String, Map, ResourceSet)} for cases where
	 * the EClass instance is already available.
	 *
	 * @param eClass      the target EClass for deserialization
	 * @param propertyMap the key-value map to deserialize
	 * @param resourceSet the resource set used for loading
	 * @return the deserialized EObject, or {@code null} if loading fails or types don't match
	 */
	public static EObject loadEObject(EClass eClass, Map<String, Object> propertyMap, ResourceSet resourceSet)  {
		return loadEObject(eClass, propertyMap, resourceSet, new ArrayList<>());
	}

	/**
	 * Deserializes a property map into an EMF EObject, collecting the codec's own
	 * load diagnostics into the given list.
	 * <p>
	 * Overload of {@link #loadEObject(EClass, Map, ResourceSet)} for callers that
	 * have to report <i>why</i> a load produced nothing, or that a load succeeded
	 * while the codec still complained. The plain overload discards those
	 * diagnostics, which makes a partially applied payload indistinguishable from
	 * a clean one.
	 *
	 * @param eClass         the target EClass for deserialization
	 * @param propertyMap    the key-value map to deserialize
	 * @param resourceSet    the resource set used for loading
	 * @param diagnosticsOut collects the messages of {@link Resource#getErrors()}
	 *                       and {@link Resource#getWarnings()}, plus the message of
	 *                       an {@link IOException}; never cleared, only appended to
	 * @return the deserialized EObject, or {@code null} if loading fails or types don't match
	 */
	public static EObject loadEObject(EClass eClass, Map<String, Object> propertyMap, ResourceSet resourceSet,
			List<String> diagnosticsOut) {
		try {
			String jsonString = mapToJsonString(propertyMap);
			InputStream inputStream = stringToInputStream(jsonString);
			Resource resource = resourceSet.createResource(STRUCTURED_OUTPUT_URI);
			Map<String, Object> options = new HashMap<>();
			options.put(CodecResource.CODEC_ROOT_TYPE, eClass);
			options.put(ConfigProperty.USE_NAMES_FROM_EXTENDED_METADATA.getKey(), true);
			resource.load(inputStream, options);
			collectDiagnostics(resource, diagnosticsOut);
			 if(!resource.getContents().isEmpty() && resource.getContents().get(0).eClass().equals(eClass)) {
				return resource.getContents().get(0);
			}

		} catch (IOException e) {
			LOGGER.severe(String.format("IOException when trying to load structured output into known EObject of EClass %s", eClass.getName()));
			diagnosticsOut.add(e.getMessage() == null ? e.toString() : e.getMessage());
		}
		return null;

	}

	/**
	 * Appends the resource's error and warning messages to the given list. A codec
	 * that cannot place a value reports it here and nowhere else, so a caller that
	 * never reads them cannot tell a complete load from a lossy one.
	 */
	private static void collectDiagnostics(Resource resource, List<String> diagnosticsOut) {
		for (Resource.Diagnostic diagnostic : resource.getErrors()) {
			diagnosticsOut.add(diagnostic.getMessage());
		}
		for (Resource.Diagnostic diagnostic : resource.getWarnings()) {
			diagnosticsOut.add(diagnostic.getMessage());
		}
	}

	private static InputStream stringToInputStream(String jsonString) {
		// Wrap the string bytes in a ByteArrayInputStream, using UTF-8 encoding
		return new ByteArrayInputStream(jsonString.getBytes(StandardCharsets.UTF_8));
	}

	private static String mapToJsonString(Map<String, Object> propertyMap) throws IOException {
		ObjectMapper mapper = new ObjectMapper();

		// Convert the Map into a JSON formatted String
		String jsonString = mapper.writeValueAsString(propertyMap);

		// Note: The resulting JSON string needs to be wrapped if your EMF resource expects a root element.
		// If your EMF model root object is mapped directly from the properties, this is often sufficient.

		return jsonString;
	}
}
