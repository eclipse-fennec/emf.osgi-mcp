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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.fennec.codec.config.ConfigProperty;
import org.eclipse.fennec.codec.jsonschema.v2.constants.CodecJsonSchemaOptions;
import org.eclipse.fennec.codec.metadata.model.codec.TypeStrategy;

import tools.jackson.databind.json.JsonMapper;

/**
 * Base implementation of {@link MCPTool} providing field storage for tool metadata
 * and utility methods for loading JSON schemas from the filesystem or from
 * EMF Ecore models via the Fennec JSON Schema codec.
 * <p>
 * Subclasses typically set {@link #name}, {@link #description}, and
 * {@link #inputSchema} in their {@code @Activate} method.
 *
 * @author ilenia
 * @since Jan 23, 2026
 */
public abstract class AbstractMCPTool implements MCPTool {
	
	protected String name;
	protected String description;
	protected String inputSchema;
	protected String outputSchema;

	/* 
	 * (non-Javadoc)
	 * @see org.eclipse.fennec.ai.mcp.api.MCPTool#getName()
	 */
	@Override
	public String getName() {
		return name;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.eclipse.fennec.ai.mcp.api.MCPTool#getDescription()
	 */
	@Override
	public String getDescription() {
		return description;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.eclipse.fennec.ai.mcp.api.MCPTool#getInputSchema()
	 */
	@Override
	public String getInputSchema() {
		return inputSchema;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.eclipse.fennec.ai.mcp.api.MCPTool#getOutputSchema()
	 */
	@Override
	public String getOutputSchema() {
		return outputSchema;
	}
	
		
	/**
	 * Loads a JSON schema from a file path on the filesystem.
	 * @param schemaPath absolute or relative path to the schema file
	 * @return the schema content as a UTF-8 string
	 * @throws IOException if the path is null/empty or the file does not exist
	 */
	protected String loadSchema(String schemaPath) throws IOException {
		if(schemaPath == null || schemaPath.isEmpty()) {
			throw new IOException("Null or empty file path");
		}
		Path path = Paths.get(schemaPath);

	    // Check if the file exists before attempting to read it
	    if (!Files.exists(path)) {
	        throw new IOException("File not found at path: " + schemaPath);
	    }

	    // Read all bytes from the file into a String
	    return Files.readString(path, StandardCharsets.UTF_8);
	}
	
	/**
	 * Generates a JSON schema from an EMF EClass identified by its URI.
	 * Uses the Fennec JSON Schema codec to serialize the EClass into a
	 * self-contained JSON schema (all $refs inlined, no vendor extensions).
	 *
	 * @param eClassUri  the EMF URI of the EClass to convert (e.g. {@code platform:/...#//MyClass})
	 * @param resourceSet the EMF resource set capable of resolving the URI
	 * @return the generated JSON schema as a string
	 * @throws IOException if serialization fails
	 * @throws IllegalStateException if the URI does not resolve to an EClass
	 */
	protected String loadSchema(String eClassUri, ResourceSet resourceSet) throws IOException {
		EObject eObj = resourceSet.getEObject(URI.createURI(eClassUri), false);
		if(eObj == null || !(eObj instanceof EClass)) {
			throw new IllegalStateException(String.format("EClass URI %s is null or is not an EClass", eClassUri));
		}
		EClass eClass =(EClass) eObj;
		Resource resource = resourceSet.createResource(URI.createURI(UUID.randomUUID().toString().concat(".jsonschema")));
		try {
			// serialize a copy - Resource#getContents() is a containment list, so adding the
			// live EClass would re-parent it out of its (registered, shared) EPackage
			resource.getContents().add(EcoreUtil.copy(eClass));
			Map<String, Object> options = new HashMap<>();
			options.put(CodecJsonSchemaOptions.OPTION_INLINE_REFS, true);
			options.put(CodecJsonSchemaOptions.OPTION_SUPPRESS_VENDOR_EXTENSIONS, true);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			resource.save(out, options);
			return out.toString(StandardCharsets.UTF_8);
		} finally {
			resourceSet.getResources().remove(resource);
		}
	}
	
	/**
	 * Serializes an EMF EObject to a JSON map suitable for MCP tool output.
	 * Uses Fennec codec with no type strategy and no ID keys, so the output
	 * is a clean property map matching the EObject's structural features.
	 *
	 * @param eObject     the EMF object to serialize
	 * @param resourceSet the resource set used for serialization
	 * @return a map representation of the EObject's JSON serialization
	 * @throws IOException if serialization fails
	 */
	protected Map<String, Object> saveEObjectToString(EObject eObject, ResourceSet resourceSet) throws IOException {
		Resource resource = resourceSet.createResource(URI.createURI(UUID.randomUUID().toString().concat(".json")));
		try {
			// serialize a copy - adding the live object would move it out of the resource
			// (or container) that owns it, emptying the caller's model
			resource.getContents().add(EcoreUtil.copy(eObject));
			Map<String, Object> options = new HashMap<>();
			options.put(ConfigProperty.SERIALIZE_NULL.getKey(), false);
			options.put(ConfigProperty.TYPE_STRATEGY.getKey(), TypeStrategy.NONE);
			options.put(ConfigProperty.ID_KEY_MODE.getKey(), "NONE");
			options.put(ConfigProperty.SERIALIZE_DEFAULT.getKey(), true);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			resource.save(out, options);
			String json = out.toString(StandardCharsets.UTF_8);
			@SuppressWarnings("unchecked")
			Map<String, Object> map = JsonMapper.builder().build().readValue(json, Map.class);
			return map;
		} finally {
			resourceSet.getResources().remove(resource);
		}
	}
}
