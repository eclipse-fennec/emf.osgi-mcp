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

import java.util.List;
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
	private AddETypeParameterTool addETypeParameter;
	private AddEAttributeTool addEAttribute;
	private AddEReferenceTool addEReference;
	private RegisterPackageTool registerPackage;
	private ListRegistryTool listRegistry;
	private ExportDatasetTool exportDataset;
	private CreateInstanceTool createInstance;
	private ModifyFeatureTool modifyFeature;
	private ImportEcoreTool importEcore;
	private ImportInstancesTool importInstances;

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
		addETypeParameter = new AddETypeParameterTool();
		addETypeParameter.registry = registry;
		addETypeParameter.guard = guard;
		addETypeParameter.activate();
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
		importEcore = new ImportEcoreTool();
		importEcore.registry = registry;
		importEcore.packages = packages;
		importEcore.activate();
		importInstances = new ImportInstancesTool();
		importInstances.registry = registry;
		importInstances.packages = packages;
		importInstances.activate();
	}

	@Test
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

	@Test
	void authorGenericClassWithParameterizedSuperType() {
		String metaDs = (String) call(createDataset, Map.of()).get("datasetId");
		String pkgId = (String) call(createEPackage, Map.of("datasetId", metaDs, "name", "gen", "nsURI", "http://example.org/gen", "nsPrefix", "gen")).get("objectId");
		// Base<T>
		String baseId = (String) call(addEClass, Map.of("datasetId", metaDs, "packageObjectId", pkgId, "name", "Base")).get("objectId");
		call(addETypeParameter, Map.of("datasetId", metaDs, "ownerObjectId", baseId, "name", "T"));
		// Derived extends Base<EString>
		call(addEClass, Map.of("datasetId", metaDs, "packageObjectId", pkgId, "name", "Derived",
				"eGenericSuperTypes", java.util.List.of(
						Map.of("classifier", baseId, "typeArguments", java.util.List.of(Map.of("classifier", ecore("EString")))))));

		String ecoreXmi = (String) call(exportDataset, Map.of("datasetId", metaDs, "validate", false)).get("content");
		assertThat(ecoreXmi).contains("eTypeParameters").contains("eGenericSuperTypes").contains("Base").contains("Derived");
	}

	@Test
	@SuppressWarnings("unchecked")
	void roundTripThroughXmiImport() {
		// author (without registering) and export the .ecore
		String metaDs = (String) call(createDataset, Map.of()).get("datasetId");
		String pkgId = (String) call(createEPackage, Map.of("datasetId", metaDs, "name", "authored", "nsURI", NS_URI, "nsPrefix", "auth")).get("objectId");
		String bookId = (String) call(addEClass, Map.of("datasetId", metaDs, "packageObjectId", pkgId, "name", "Book")).get("objectId");
		call(addEAttribute, Map.of("datasetId", metaDs, "classObjectId", bookId, "name", "title", "eType", ecore("EString")));
		String ecoreXmi = (String) call(exportDataset, Map.of("datasetId", metaDs, "validate", false)).get("content");

		// import the .ecore: registers the package, returns an editable dataset
		Map<String, Object> imported = call(importEcore, Map.of("xmi", ecoreXmi));
		assertThat(((List<Map<String, Object>>) (Object) imported.get("packages"))).hasSize(1);

		// the imported package is instantiable
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

		// re-import the instances against the registered package
		Map<String, Object> reimported = call(importInstances, Map.of("xmi", instanceXmi, "nsURI", NS_URI));
		assertThat(((Number) reimported.get("objectCount")).intValue()).isGreaterThanOrEqualTo(1);
		String reExported = (String) call(exportDataset, Map.of("datasetId", (String) reimported.get("datasetId"), "validate", false)).get("content");
		assertThat(reExported).contains("Dune");
	}

	@Test
	void importInstancesRequiresRegisteredMetamodel() {
		String instance = """
				<?xml version="1.0" encoding="UTF-8"?>
				<auth:Book xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI" xmlns:auth="http://example.org/authored" title="Dune"/>
				""";
		String message = callExpectingError(importInstances, Map.of("xmi", instance, "nsURI", "http://example.org/not-registered"));
		assertThat(message).contains("No registered package");
	}

	@Test
	void importEcoreRejectsDoctype() {
		String xmi = "<?xml version=\"1.0\"?>\n<!DOCTYPE x>\n<ecore:EPackage xmlns:ecore=\"http://www.eclipse.org/emf/2002/Ecore\" name=\"p\" nsURI=\"http://example.org/p\" nsPrefix=\"p\"/>";
		String message = callExpectingError(importEcore, Map.of("xmi", xmi));
		assertThat(message).contains("DOCTYPE");
	}

	private String callExpectingError(AbstractEMFTool tool, Map<String, Object> arguments) {
		McpSchema.CallToolResult result = tool.execute(exchange, arguments).block();
		assertThat(result).isNotNull();
		assertThat(result.isError()).isEqualTo(Boolean.TRUE);
		return ((McpSchema.TextContent) result.content().get(0)).text();
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
