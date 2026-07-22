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
package org.eclipse.fennec.mcp.emf.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.fennec.emf.osgi.ResourceSetFactory;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetLimits;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.ModelGuard;
import org.eclipse.fennec.mcp.emf.tools.core.PackageRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end metamodel authoring flow: build an EPackage with the authoring
 * tools, register it, instantiate one of its classes, and export both the
 * .ecore and the instance XMI.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
class AuthoringFlowTest {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final String NS_URI = "http://example.org/authored";

	private McpAsyncServerExchange exchange;
	private CreateDatasetTool createDataset;
	private CreateEPackageTool createEPackage;
	private AddEClassTool addEClass;
	private AddEAttributeTool addEAttribute;
	private AddEReferenceTool addEReference;
	private RegisterPackageTool registerPackage;
	private ListRegistryTool listRegistry;
	private ExportDatasetTool exportDataset;
	private CreateInstanceTool createInstance;
	private ModifyFeatureTool modifyFeature;

	@BeforeEach
	void setUp() throws Exception {
		ResourceSetFactory factory = ResourceSetImpl::new;
		PackageRegistry packages = newPackageRegistry(Set.of("*"), Set.of(), 100);
		ModelGuard guard = newGuard(packages);
		DatasetRegistry registry = newDatasetRegistry(factory);
		exchange = mock(McpAsyncServerExchange.class);
		when(exchange.sessionId()).thenReturn("session-1");

		createDataset = new CreateDatasetTool();
		createDataset.registry = registry;
		createDataset.activate();
		createEPackage = new CreateEPackageTool();
		createEPackage.registry = registry;
		createEPackage.activate();
		addEClass = new AddEClassTool();
		addEClass.registry = registry;
		addEClass.guard = guard;
		addEClass.activate();
		addEAttribute = new AddEAttributeTool();
		addEAttribute.registry = registry;
		addEAttribute.guard = guard;
		addEAttribute.activate();
		addEReference = new AddEReferenceTool();
		addEReference.registry = registry;
		addEReference.guard = guard;
		addEReference.activate();
		registerPackage = new RegisterPackageTool();
		registerPackage.registry = registry;
		registerPackage.packages = packages;
		registerPackage.activate();
		listRegistry = new ListRegistryTool();
		listRegistry.packages = packages;
		listRegistry.activate();
		exportDataset = new ExportDatasetTool();
		exportDataset.registry = registry;
		exportDataset.activate();
		createInstance = new CreateInstanceTool();
		createInstance.guard = guard;
		createInstance.registry = registry;
		createInstance.activate();
		modifyFeature = new ModifyFeatureTool();
		modifyFeature.registry = registry;
		modifyFeature.guard = guard;
		modifyFeature.activate();
	}

	@Test
	@SuppressWarnings("unchecked")
	void authorRegisterInstantiateExport() {
		// author the metamodel
		String metaDs = (String) call(createDataset, Map.of()).get("datasetId");
		String pkgId = (String) call(createEPackage, Map.of("datasetId", metaDs, "name", "authored", "nsURI", NS_URI, "nsPrefix", "auth")).get("objectId");
		String authorId = (String) call(addEClass, Map.of("datasetId", metaDs, "packageObjectId", pkgId, "name", "Author")).get("objectId");
		call(addEAttribute, Map.of("datasetId", metaDs, "classObjectId", authorId, "name", "name", "eType", ecore("EString")));
		String bookId = (String) call(addEClass, Map.of("datasetId", metaDs, "packageObjectId", pkgId, "name", "Book")).get("objectId");
		call(addEAttribute, Map.of("datasetId", metaDs, "classObjectId", bookId, "name", "title", "eType", ecore("EString")));
		// reference eType by a dataset-local objectId (the authored Author class)
		call(addEReference, Map.of("datasetId", metaDs, "classObjectId", bookId, "name", "writtenBy", "eType", authorId));

		// register: validates, becomes instantiable
		Map<String, Object> registered = call(registerPackage, Map.of("datasetId", metaDs, "packageObjectId", pkgId));
		assertThat(registered.get("valid")).isEqualTo(Boolean.TRUE);
		assertThat(((Number) registered.get("registeredClasses")).intValue()).isEqualTo(2);

		Map<String, Object> registryList = call(listRegistry, Map.of());
		assertThat(registryList.get("packages").toString()).contains(NS_URI).contains("#//Book");

		// export the .ecore
		String ecoreXmi = (String) call(exportDataset, Map.of("datasetId", metaDs, "validate", false)).get("content");
		assertThat(ecoreXmi).contains("Author").contains("Book").contains("writtenBy").contains(NS_URI);

		// instantiate the authored class in a fresh dataset and populate it
		String instanceDs = (String) call(createDataset, Map.of()).get("datasetId");
		String bookInstance = (String) call(createInstance, Map.of("datasetId", instanceDs, "eClass", NS_URI + "#//Book")).get("objectId");
		Map<String, Object> args = new java.util.HashMap<>();
		args.put("datasetId", instanceDs);
		args.put("objectId", bookInstance);
		args.put("feature", "title");
		args.put("action", "set");
		args.put("value", "Dune");
		call(modifyFeature, args);

		String instanceXmi = (String) call(exportDataset, Map.of("datasetId", instanceDs, "validate", false)).get("content");
		assertThat(instanceXmi).contains("Dune");
	}

	private static String ecore(String name) {
		return EcorePackage.eNS_URI + "#//" + name;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> call(AbstractEMFTool tool, Map<String, Object> arguments) {
		McpSchema.CallToolResult result = tool.execute(exchange, arguments).block();
		assertThat(result).isNotNull();
		String text = ((McpSchema.TextContent) result.content().get(0)).text();
		assertThat(result.isError()).as("tool error: %s", text).isNotEqualTo(Boolean.TRUE);
		return MAPPER.readValue(text, Map.class);
	}

	private static PackageRegistry newPackageRegistry(Set<String> allow, Set<String> deny, int maxModels) throws Exception {
		var ctor = PackageRegistry.class.getDeclaredConstructor(Set.class, Set.class, int.class);
		ctor.setAccessible(true);
		return ctor.newInstance(allow, deny, maxModels);
	}

	private static ModelGuard newGuard(PackageRegistry packages) throws Exception {
		var ctor = ModelGuard.class.getDeclaredConstructor(EPackage.Registry.class, PackageRegistry.class, Set.class, Set.class);
		ctor.setAccessible(true);
		return ctor.newInstance(new org.eclipse.emf.ecore.impl.EPackageRegistryImpl(), packages, Set.of(), Set.of());
	}

	private static DatasetRegistry newDatasetRegistry(ResourceSetFactory factory) throws Exception {
		var ctor = DatasetRegistry.class.getDeclaredConstructor(ResourceSetFactory.class, DatasetLimits.class);
		ctor.setAccessible(true);
		return ctor.newInstance(factory, DatasetLimits.defaults());
	}
}
