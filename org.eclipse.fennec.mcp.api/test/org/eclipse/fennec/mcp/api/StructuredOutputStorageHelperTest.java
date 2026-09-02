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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.fennec.codec.resource.CodecResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * How a tool's structured output map becomes a typed EObject.
 * <p>
 * The codec does the parsing; what this helper contributes is the wiring around it - the
 * map serialized to JSON, the root type handed to the codec through
 * {@code CodecResource.CODEC_ROOT_TYPE}, and above all the guard afterwards: a load that
 * produced nothing, produced the wrong type, or failed outright has to come back as
 * {@code null} rather than as an object of a type the caller did not ask for. The codec is
 * stubbed here so each of those outcomes can be produced deliberately; that it parses
 * correctly is the codec's own business.
 */
class StructuredOutputStorageHelperTest {

	private static final String NS_URI = "http://example.org/mcp/structured-output/test";
	private static final Map<String, Object> PROPERTIES = Map.of("name", "value");

	private EClass thing;
	private EClass other;
	private ResourceSet resourceSet;
	private Resource resource;

	@BeforeEach
	void setUp() {
		EPackage ePackage = EcoreFactory.eINSTANCE.createEPackage();
		ePackage.setName("test");
		ePackage.setNsPrefix("test");
		ePackage.setNsURI(NS_URI);
		thing = EcoreFactory.eINSTANCE.createEClass();
		thing.setName("Thing");
		other = EcoreFactory.eINSTANCE.createEClass();
		other.setName("Other");
		ePackage.getEClassifiers().add(thing);
		ePackage.getEClassifiers().add(other);
		EPackage.Registry.INSTANCE.put(NS_URI, ePackage);

		resource = mock(Resource.class);
		resourceSet = mock(ResourceSet.class);
		when(resourceSet.createResource(any())).thenReturn(resource);
		when(resource.getContents()).thenReturn(new BasicEList<>());
	}

	@Test
	@DisplayName("the map is handed to the codec as JSON, with the EClass as root type")
	void theMapIsPassedThroughAsJsonWithItsRootType() throws Exception {
		loadYields(EcoreUtil.create(thing));

		EObject loaded = StructuredOutputStorageHelper.loadEObject(thing, PROPERTIES, resourceSet);

		assertThat(loaded).isNotNull();
		ArgumentCaptor<InputStream> content = ArgumentCaptor.forClass(InputStream.class);
		ArgumentCaptor<Map<String, Object>> options = captorOfMap();
		org.mockito.Mockito.verify(resource).load(content.capture(), options.capture());
		assertThat(new String(content.getValue().readAllBytes(), StandardCharsets.UTF_8))
				.as("the property map reaches the codec as JSON")
				.isEqualTo("{\"name\":\"value\"}");
		assertThat(options.getValue())
				.as("without the root type the codec has nothing to deserialize into")
				.containsEntry(CodecResource.CODEC_ROOT_TYPE, thing)
				.containsEntry("useNamesFromExtendedMetadata", true);
	}

	@Test
	@DisplayName("a load of the requested type is returned")
	void aMatchingTypeIsReturned() throws Exception {
		EObject expected = EcoreUtil.create(thing);
		loadYields(expected);

		assertThat(StructuredOutputStorageHelper.loadEObject(thing, PROPERTIES, resourceSet)).isSameAs(expected);
	}

	@Test
	@DisplayName("a load of a different type is refused")
	void aDifferentTypeIsRefused() throws Exception {
		loadYields(EcoreUtil.create(other));

		assertThat(StructuredOutputStorageHelper.loadEObject(thing, PROPERTIES, resourceSet))
				.as("handing back an object of a type the caller did not ask for would fail "
						+ "further away, in whatever reads its features")
				.isNull();
	}

	@Test
	@DisplayName("a load that produced nothing is not mistaken for a result")
	void anEmptyLoadYieldsNull() throws Exception {
		// resource.load leaves the contents empty: no exception, no root object.
		assertThat(StructuredOutputStorageHelper.loadEObject(thing, PROPERTIES, resourceSet)).isNull();
	}

	@Test
	@DisplayName("a failing load is reported as no result rather than thrown")
	void aFailingLoadYieldsNull() throws Exception {
		doThrow(new IOException("codec said no")).when(resource).load(any(), any());

		// The caller is a tool result handler; an IOException from the codec must not
		// escape into the MCP dispatch as an unrelated failure.
		assertThat(StructuredOutputStorageHelper.loadEObject(thing, PROPERTIES, resourceSet)).isNull();
	}

	@Test
	@DisplayName("the EClass can be named by URI instead of passed in")
	void theClassUriOverloadResolvesTheType() throws Exception {
		String classUri = EcoreUtil.getURI(thing).toString();
		when(resourceSet.getEObject(eq(URI.createURI(classUri)), eq(false))).thenReturn(thing);
		loadYields(EcoreUtil.create(thing));

		EObject loaded = StructuredOutputStorageHelper.loadEObject(classUri, PROPERTIES, resourceSet);

		assertThat(loaded).isNotNull();
		assertThat(EcoreUtil.getURI(loaded.eClass()).toString()).isEqualTo(classUri);
	}

	@Test
	@DisplayName("the URI overload refuses a load of another type just the same")
	void theClassUriOverloadRefusesAnotherType() throws Exception {
		String classUri = EcoreUtil.getURI(thing).toString();
		when(resourceSet.getEObject(eq(URI.createURI(classUri)), eq(false))).thenReturn(thing);
		loadYields(EcoreUtil.create(other));

		assertThat(StructuredOutputStorageHelper.loadEObject(classUri, PROPERTIES, resourceSet)).isNull();
	}

	/** Makes the stubbed codec resource produce {@code root} when it is loaded. */
	private void loadYields(EObject root) throws IOException {
		EList<EObject> contents = new BasicEList<>();
		when(resource.getContents()).thenReturn(contents);
		doAnswer(invocation -> {
			contents.add(root);
			return null;
		}).when(resource).load(any(), any());
	}

	@SuppressWarnings("unchecked")
	private static ArgumentCaptor<Map<String, Object>> captorOfMap() {
		return ArgumentCaptor.forClass(Map.class);
	}
}
