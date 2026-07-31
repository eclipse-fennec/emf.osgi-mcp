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
package org.eclipse.fennec.mcp.service.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.fennec.codec.config.ConfigProperty;
import org.eclipse.fennec.codec.jsonschema.v2.constants.CodecJsonSchemaOptions;
import org.eclipse.fennec.codec.metadata.model.codec.TypeStrategy;
import org.eclipse.fennec.emf.osgi.ResourceSetFactory;
import org.eclipse.fennec.mcp.api.StructuredOutputStorageHelper;
import org.eclipse.fennec.mcp.service.tools.ServiceClientToolBridge.SchemaGenerator;
import org.eclipse.fennec.mcp.service.tools.ServiceOperationTool.PayloadCodec;
import org.eclipse.fennec.service.api.ServiceInvocationException;
import org.eclipse.fennec.service.api.ServiceOperation;

/**
 * Codec-backed implementation of the bridge's conversion seams: JSON schema
 * generation via the Fennec JSON Schema codec (self-contained, refs inlined),
 * request deserialization via {@link StructuredOutputStorageHelper} and
 * response serialization via the codec JSON resource (no type discriminator,
 * a clean property map for the model).
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
class CodecPayloads implements SchemaGenerator, PayloadCodec {

	static final String EMPTY_OBJECT_SCHEMA = """
			{ "type": "object", "properties": {} }
			""";

	private final ResourceSetFactory resourceSetFactory;

	CodecPayloads(ResourceSetFactory resourceSetFactory) {
		this.resourceSetFactory = resourceSetFactory;
	}

	@Override
	public String schemaFor(EClass eClass) {
		if (eClass == null) {
			return EMPTY_OBJECT_SCHEMA;
		}
		// serialize a copy — adding the original to the schema resource would
		// re-parent it out of its (imported, live) EPackage
		Resource resource = resourceSetFactory.createResourceSet()
				.createResource(URI.createURI(UUID.randomUUID() + ".jsonschema"));
		resource.getContents().add(EcoreUtil.copy(eClass));
		Map<String, Object> options = new HashMap<>();
		options.put(CodecJsonSchemaOptions.OPTION_INLINE_REFS, true);
		options.put(CodecJsonSchemaOptions.OPTION_SUPPRESS_VENDOR_EXTENSIONS, true);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			resource.save(out, options);
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException(String.format("Cannot generate the JSON schema for EClass '%s'", eClass.getName()), e);
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	@Override
	public EObject toRequest(ServiceOperation operation, Map<String, Object> arguments) {
		EObject request = StructuredOutputStorageHelper.loadEObject(operation.requestType(), arguments,
				resourceSetFactory.createResourceSet());
		if (request == null) {
			throw new ServiceInvocationException(String.format(
					"The arguments do not match the request schema of operation '%s'", operation.name()));
		}
		return request;
	}

	@Override
	public String toJson(EObject response) {
		Resource resource = resourceSetFactory.createResourceSet()
				.createResource(URI.createURI(UUID.randomUUID() + ".json"));
		resource.getContents().add(response);
		Map<String, Object> options = new HashMap<>();
		options.put(ConfigProperty.SERIALIZE_NULL.getKey(), false);
		options.put(ConfigProperty.TYPE_STRATEGY.getKey(), TypeStrategy.NONE);
		options.put(ConfigProperty.ID_KEY_MODE.getKey(), "NONE");
		options.put(ConfigProperty.SERIALIZE_DEFAULT.getKey(), true);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			resource.save(out, options);
		} catch (IOException | RuntimeException e) {
			throw new ServiceInvocationException("Cannot serialize the service response", e);
		}
		return out.toString(StandardCharsets.UTF_8);
	}
}
