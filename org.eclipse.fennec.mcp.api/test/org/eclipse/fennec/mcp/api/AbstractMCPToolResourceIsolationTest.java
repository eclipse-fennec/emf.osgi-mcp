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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import reactor.core.publisher.Mono;

/**
 * Verifies that the serialization helpers of {@link AbstractMCPTool} do not
 * re-parent the objects they are asked to serialize. {@code Resource#getContents()}
 * is a containment list, so adding a live {@link EClass} or {@link EObject} to a
 * scratch resource would move it out of the model that owns it - a shared-state
 * mutation for registered {@link EPackage}s, and data loss for dataset roots.
 * The scratch resources must not linger in the caller's {@link ResourceSet} either.
 *
 * @author ilenia
 */
class AbstractMCPToolResourceIsolationTest {

	private static final String NS_URI = "http://fennec.eclipse.org/mcp/test/1.0";

	/** Records what a scratch resource was asked to serialize. */
	private final List<EObject> serialized = new ArrayList<>();

	private ResourceSet resourceSet;
	private Resource modelResource;
	private EPackage ePackage;
	private EClass personClass;

	private final AbstractMCPTool tool = new AbstractMCPTool() {

		@Override
		public Mono<CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
			return Mono.empty();
		}
	};

	@BeforeEach
	void setUp() {
		ePackage = EcoreFactory.eINSTANCE.createEPackage();
		ePackage.setName("test");
		ePackage.setNsPrefix("test");
		ePackage.setNsURI(NS_URI);

		personClass = EcoreFactory.eINSTANCE.createEClass();
		personClass.setName("Person");
		EAttribute name = EcoreFactory.eINSTANCE.createEAttribute();
		name.setName("name");
		name.setEType(EcorePackage.eINSTANCE.getEString());
		personClass.getEStructuralFeatures().add(name);
		ePackage.getEClassifiers().add(personClass);

		resourceSet = new ResourceSetImpl();
		resourceSet.getPackageRegistry().put(NS_URI, ePackage);
		modelResource = new ResourceImpl(URI.createURI(NS_URI));
		modelResource.getContents().add(ePackage);
		resourceSet.getResources().add(modelResource);

		Resource.Factory factory = uri -> new RecordingResource(uri, serialized);
		resourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("jsonschema", factory);
		resourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("json", factory);
	}

	@Test
	void loadSchema_leavesTheLiveEClassInItsPackage() throws IOException {
		String schema = tool.loadSchema(NS_URI + "#//Person", resourceSet);

		assertThat(schema).contains("Person");
		assertThat(personClass.getEPackage()).isSameAs(ePackage);
		assertThat(personClass.eResource()).isSameAs(modelResource);
		assertThat(personClass.eContainer()).isSameAs(ePackage);
		assertThat(ePackage.getEClassifiers()).containsExactly(personClass);
	}

	@Test
	void loadSchema_serializesACopyAndDropsTheScratchResource() throws IOException {
		tool.loadSchema(NS_URI + "#//Person", resourceSet);

		assertThat(serialized).hasSize(1);
		assertThat(serialized.get(0)).isNotSameAs(personClass);
		assertThat(((EClass) serialized.get(0)).getName()).isEqualTo("Person");
		assertThat(resourceSet.getResources()).containsExactly(modelResource);
	}

	@Test
	void loadSchema_removesTheScratchResourceEvenWhenSerializationFails() {
		resourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("jsonschema",
				(Resource.Factory) FailingResource::new);

		assertThat(catchIOException(() -> tool.loadSchema(NS_URI + "#//Person", resourceSet))).isNotNull();
		assertThat(resourceSet.getResources()).containsExactly(modelResource);
	}

	@Test
	void saveEObjectToString_leavesTheLiveObjectInItsResource() throws IOException {
		EObject person = EcoreUtil.create(personClass);
		Resource instanceResource = new ResourceImpl(URI.createURI("test-instance.xmi"));
		instanceResource.getContents().add(person);
		resourceSet.getResources().add(instanceResource);

		Map<String, Object> json = tool.saveEObjectToString(person, resourceSet);

		assertThat(json).containsEntry("eClass", "Person");
		assertThat(person.eResource()).isSameAs(instanceResource);
		assertThat(instanceResource.getContents()).containsExactly(person);
		assertThat(serialized).hasSize(1);
		assertThat(serialized.get(0)).isNotSameAs(person);
		assertThat(resourceSet.getResources()).containsExactly(modelResource, instanceResource);
	}

	private static IOException catchIOException(ThrowingCall call) {
		try {
			call.run();
			return null;
		} catch (IOException e) {
			return e;
		}
	}

	@FunctionalInterface
	private interface ThrowingCall {
		void run() throws IOException;
	}

	/**
	 * Stand-in for the Fennec codec resources: records the contents it is asked to
	 * write and emits a trivial JSON document naming the serialized object.
	 */
	private static class RecordingResource extends ResourceImpl {

		private final List<EObject> serialized;

		RecordingResource(URI uri, List<EObject> serialized) {
			super(uri);
			this.serialized = serialized;
		}

		@Override
		protected void doSave(OutputStream outputStream, Map<?, ?> options) throws IOException {
			EObject content = getContents().get(0);
			serialized.add(content);
			String name = content instanceof EClass eClass ? eClass.getName() : content.eClass().getName();
			outputStream.write(("{\"eClass\":\"" + name + "\"}").getBytes(StandardCharsets.UTF_8));
		}
	}

	/** Stand-in for a codec that is missing or fails on the given content. */
	private static class FailingResource extends ResourceImpl {

		FailingResource(URI uri) {
			super(uri);
		}

		@Override
		protected void doSave(OutputStream outputStream, Map<?, ?> options) throws IOException {
			throw new IOException("no codec available");
		}
	}
}
