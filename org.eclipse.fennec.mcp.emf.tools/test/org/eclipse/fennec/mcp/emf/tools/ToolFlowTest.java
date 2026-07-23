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

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.impl.EPackageRegistryImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.fennec.emf.osgi.ResourceSetFactory;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetLimits;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.ModelGuard;
import org.eclipse.fennec.mcp.emf.tools.core.TestModels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end flow over the MCP tools with a mocked exchange: discovery,
 * dataset build, inspection, export, deny-all enforcement and byte-identical
 * recipe replay.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
class ToolFlowTest {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private ModelGuard guard;
	private DatasetRegistry registry;
	private McpAsyncServerExchange exchange;

	private ListMetamodelTool listMetamodel;
	private DescribeEClassTool describeEClass;
	private CreateDatasetTool createDataset;
	private CreateInstanceTool createInstance;
	private ModifyFeatureTool modifyFeature;
	private DeleteInstanceTool deleteInstance;
	private InspectDatasetTool inspectDataset;
	private ManageDatasetTool manageDataset;
	private ExportDatasetTool exportDataset;
	private ReplayRecipeTool replayRecipe;

	@BeforeEach
	void setUp() throws Exception {
		EPackage libraryPackage = TestModels.libraryPackage();
		EPackage.Registry packageRegistry = TestModels.registryWith(libraryPackage);
		ResourceSetFactory factory = () -> {
			ResourceSetImpl resourceSet = new ResourceSetImpl();
			resourceSet.setPackageRegistry(new EPackageRegistryImpl(packageRegistry));
			return resourceSet;
		};
		guard = guardFor(packageRegistry, Set.of(TestModels.NS_URI), Set.of(TestModels.LIBRARY, TestModels.BOOK, TestModels.WRITER));
		registry = registryFor(factory, DatasetLimits.defaults());
		exchange = mock(McpAsyncServerExchange.class);
		when(exchange.sessionId()).thenReturn("session-1");

		listMetamodel = new ListMetamodelTool();
		listMetamodel.guard = guard;
		listMetamodel.activate();
		describeEClass = new DescribeEClassTool();
		describeEClass.guard = guard;
		describeEClass.activate();
		createDataset = new CreateDatasetTool();
		createDataset.registry = registry;
		createDataset.activate();
		createInstance = new CreateInstanceTool();
		createInstance.guard = guard;
		createInstance.registry = registry;
		createInstance.activate();
		modifyFeature = new ModifyFeatureTool();
		modifyFeature.registry = registry;
		modifyFeature.guard = guard;
		modifyFeature.activate();
		deleteInstance = new DeleteInstanceTool();
		deleteInstance.registry = registry;
		deleteInstance.activate();
		inspectDataset = new InspectDatasetTool();
		inspectDataset.registry = registry;
		inspectDataset.activate();
		manageDataset = new ManageDatasetTool();
		manageDataset.guard = guard;
		manageDataset.registry = registry;
		manageDataset.activate();
		exportDataset = new ExportDatasetTool();
		exportDataset.registry = registry;
		exportDataset.activate();
		replayRecipe = new ReplayRecipeTool();
		replayRecipe.guard = guard;
		replayRecipe.registry = registry;
		replayRecipe.activate();
	}

	private static ModelGuard guardFor(EPackage.Registry packageRegistry, Set<String> packages, Set<String> classes) throws Exception {
		// the test constructor is package-private in .core — reach it reflectively from this package
		var constructor = ModelGuard.class.getDeclaredConstructor(EPackage.Registry.class, Set.class, Set.class);
		constructor.setAccessible(true);
		return constructor.newInstance(packageRegistry, packages, classes);
	}

	private static DatasetRegistry registryFor(ResourceSetFactory factory, DatasetLimits limits) throws Exception {
		var constructor = DatasetRegistry.class.getDeclaredConstructor(ResourceSetFactory.class, DatasetLimits.class);
		constructor.setAccessible(true);
		return constructor.newInstance(factory, limits);
	}

	private static DatasetRegistry registryFor(ResourceSetFactory factory, DatasetLimits limits, Path workDir) throws Exception {
		var constructor = DatasetRegistry.class.getDeclaredConstructor(ResourceSetFactory.class, DatasetLimits.class, Path.class);
		constructor.setAccessible(true);
		return constructor.newInstance(factory, limits, workDir);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> call(AbstractEMFTool tool, Map<String, Object> arguments) {
		McpSchema.CallToolResult result = tool.execute(exchange, arguments).block();
		assertThat(result).isNotNull();
		String text = ((McpSchema.TextContent) result.content().get(0)).text();
		assertThat(result.isError()).as("tool error: %s", text).isNotEqualTo(Boolean.TRUE);
		return MAPPER.readValue(text, Map.class);
	}

	private String callExpectingError(AbstractEMFTool tool, Map<String, Object> arguments) {
		McpSchema.CallToolResult result = tool.execute(exchange, arguments).block();
		assertThat(result).isNotNull();
		assertThat(result.isError()).isEqualTo(Boolean.TRUE);
		return ((McpSchema.TextContent) result.content().get(0)).text();
	}

	@SuppressWarnings("unchecked")
	@Test
	void discoveryShowsOnlyAllowListedClasses() {
		Map<String, Object> packages = call(listMetamodel, Map.of());
		assertThat((List<Map<String, Object>>) ((Object) packages.get("ePackages"))).hasSize(1);

		Map<String, Object> classes = call(listMetamodel, Map.of("nsURI", TestModels.NS_URI));
		List<Map<String, Object>> eClasses = (List<Map<String, Object>>) (Object) classes.get("eClasses");
		assertThat(eClasses).extracting(c -> c.get("name")).containsExactly("Book", "Library", "Writer");

		Map<String, Object> description = call(describeEClass, Map.of("eClass", TestModels.BOOK));
		List<Map<String, Object>> features = (List<Map<String, Object>>) (Object) description.get("features");
		assertThat(features).extracting(f -> f.get("name")).contains("title", "pages", "genre", "tags", "author");
		Map<String, Object> genre = features.stream().filter(f -> "genre".equals(f.get("name"))).findFirst().orElseThrow();
		assertThat((List<String>) (Object) genre.get("enumLiterals")).containsExactly("FANTASY", "SCIFI");
	}

	@Test
	void denyAllBlocksUnknownAndUnlistedClasses() {
		assertThat(callExpectingError(describeEClass, Map.of("eClass", TestModels.ABSTRACT_ITEM)))
				.contains("not allow-listed");
		Map<String, Object> created = call(createDataset, Map.of());
		String datasetId = (String) created.get("datasetId");
		assertThat(callExpectingError(createInstance, Map.of("datasetId", datasetId, "eClass", "http://evil.org#//Thing")))
				.contains("not allow-listed");
	}

	@SuppressWarnings("unchecked")
	@Test
	void buildInspectExportRoundtrip() {
		String datasetId = (String) call(createDataset, Map.of("seed", 42)).get("datasetId");
		String libraryId = (String) call(createInstance, Map.of("datasetId", datasetId, "eClass", TestModels.LIBRARY)).get("objectId");
		String bookId = (String) call(createInstance, Map.of("datasetId", datasetId, "eClass", TestModels.BOOK)).get("objectId");
		String writerId = (String) call(createInstance, Map.of("datasetId", datasetId, "eClass", TestModels.WRITER)).get("objectId");

		call(modifyFeature, args(datasetId, libraryId, "name", "set", "City Library"));
		call(modifyFeature, args(datasetId, libraryId, "books", "add", bookId));
		call(modifyFeature, args(datasetId, libraryId, "writers", "add", writerId));
		call(modifyFeature, args(datasetId, bookId, "title", "set", "Dune"));
		call(modifyFeature, args(datasetId, bookId, "pages", "set", 412));
		call(modifyFeature, args(datasetId, bookId, "genre", "set", "SCIFI"));
		call(modifyFeature, args(datasetId, bookId, "tags", "add", "classic"));
		call(modifyFeature, args(datasetId, bookId, "author", "set", writerId));
		call(modifyFeature, args(datasetId, writerId, "name", "set", "Frank Herbert"));
		call(modifyFeature, args(datasetId, libraryId, "featuredBook", "set", bookId));

		Map<String, Object> inspection = call(inspectDataset, Map.of("datasetId", datasetId));
		assertThat(inspection.get("objectCount")).isEqualTo(3);
		Map<String, Object> validation = (Map<String, Object>) inspection.get("validation");
		assertThat(validation.get("valid")).isEqualTo(Boolean.TRUE);

		Map<String, Object> export = call(exportDataset, Map.of("datasetId", datasetId));
		String xmi = (String) export.get("content");
		assertThat(xmi).contains("City Library").contains("Dune").contains("Frank Herbert").contains("SCIFI");
		assertThat(export.get("rootCount")).isEqualTo(1);
	}

	@SuppressWarnings("unchecked")
	@Test
	void missingRequiredFeatureIsReportedByValidation() {
		String datasetId = (String) call(createDataset, Map.of()).get("datasetId");
		call(createInstance, Map.of("datasetId", datasetId, "eClass", TestModels.LIBRARY));
		Map<String, Object> inspection = call(inspectDataset, Map.of("datasetId", datasetId));
		Map<String, Object> validation = (Map<String, Object>) inspection.get("validation");
		assertThat(validation.get("valid")).isEqualTo(Boolean.FALSE);
		assertThat((Integer) validation.get("errorCount")).isPositive();
	}

	@Test
	void recipeReplayReproducesByteIdenticalXmi() {
		String datasetId = (String) call(createDataset, Map.of()).get("datasetId");
		String libraryId = (String) call(createInstance, Map.of("datasetId", datasetId, "eClass", TestModels.LIBRARY)).get("objectId");
		String bookId = (String) call(createInstance, Map.of("datasetId", datasetId, "eClass", TestModels.BOOK)).get("objectId");
		call(modifyFeature, args(datasetId, libraryId, "name", "set", "City Library"));
		call(modifyFeature, args(datasetId, libraryId, "books", "add", bookId));
		call(modifyFeature, args(datasetId, bookId, "title", "set", "Dune"));

		String originalXmi = (String) call(exportDataset, Map.of("datasetId", datasetId, "validate", false)).get("content");

		// regenerate in place: clear + deterministic replay
		Map<String, Object> regenerated = call(manageDataset, Map.of("datasetId", datasetId, "action", "regenerate"));
		assertThat(regenerated.get("objectCount")).isEqualTo(2);
		String regeneratedXmi = (String) call(exportDataset, Map.of("datasetId", datasetId, "validate", false)).get("content");
		assertThat(regeneratedXmi).isEqualTo(originalXmi);

		// replay the recipe into a fresh dataset
		Map<String, Object> inspection = call(inspectDataset, Map.of("datasetId", datasetId, "includeRecipe", true));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> recipe = (List<Map<String, Object>>) (Object) inspection.get("recipe");
		assertThat(recipe).hasSize(5);
		Map<String, Object> replayed = call(replayRecipe, new HashMap<>(Map.of("recipe", recipe)));
		String replayedDatasetId = (String) replayed.get("datasetId");
		assertThat(replayedDatasetId).isNotEqualTo(datasetId);
		String replayedXmi = (String) call(exportDataset, Map.of("datasetId", replayedDatasetId, "validate", false)).get("content");
		assertThat(replayedXmi).isEqualTo(originalXmi);
	}

	@Test
	void deleteInstanceClearsReferences() {
		String datasetId = (String) call(createDataset, Map.of()).get("datasetId");
		String libraryId = (String) call(createInstance, Map.of("datasetId", datasetId, "eClass", TestModels.LIBRARY)).get("objectId");
		String bookId = (String) call(createInstance, Map.of("datasetId", datasetId, "eClass", TestModels.BOOK)).get("objectId");
		call(modifyFeature, args(datasetId, libraryId, "featuredBook", "set", bookId));
		Map<String, Object> deleted = call(deleteInstance, Map.of("datasetId", datasetId, "objectId", bookId));
		assertThat(deleted.get("objectCount")).isEqualTo(1);
	}

	@Test
	void oversizedExportIsWrittenToTheWorkDir(@TempDir Path workDir) throws Exception {
		// re-wire with a tiny inline cap and a pinned working directory
		EPackage.Registry packageRegistry = TestModels.registryWith(TestModels.libraryPackage());
		ResourceSetFactory factory = () -> {
			ResourceSetImpl resourceSet = new ResourceSetImpl();
			resourceSet.setPackageRegistry(new EPackageRegistryImpl(packageRegistry));
			return resourceSet;
		};
		DatasetRegistry tinyRegistry = registryFor(factory, new DatasetLimits(16, 1000, 10_000, 65_536, 1_048_576, 64, 60_000L), workDir);
		setField(createDataset, "registry", tinyRegistry);
		setField(createInstance, "registry", tinyRegistry);
		setField(modifyFeature, "registry", tinyRegistry);
		setField(exportDataset, "registry", tinyRegistry);
		setField(manageDataset, "registry", tinyRegistry);

		String datasetId = (String) call(createDataset, Map.of()).get("datasetId");
		String libraryId = (String) call(createInstance, Map.of("datasetId", datasetId, "eClass", TestModels.LIBRARY)).get("objectId");
		call(modifyFeature, args(datasetId, libraryId, "name", "set", "A library with a name long enough to exceed the cap"));
		Map<String, Object> export = call(exportDataset, Map.of("datasetId", datasetId, "validate", false));
		assertThat(export).doesNotContainKey("content");
		assertThat(export.get("inline")).isEqualTo(Boolean.FALSE);
		assertThat((String) export.get("resourceUri")).startsWith("fennec-mcp://datasets/");
		assertThat((Integer) export.get("byteSize")).isGreaterThan(64);

		// the export lands as a file below the working directory, session-scoped
		Path file = Path.of((String) export.get("file"));
		assertThat(file).exists().hasParent(workDir.resolve(file.getParent().getFileName()));
		assertThat(Files.readString(file)).contains("A library with a name long enough to exceed the cap");

		// deleting the dataset removes the export file and its session directory
		call(manageDataset, Map.of("datasetId", datasetId, "action", "delete"));
		assertThat(file).doesNotExist();
		assertThat(file.getParent()).doesNotExist();
	}

	@Test
	void sessionWithoutIdIsRejected() {
		when(exchange.sessionId()).thenReturn(null);
		String message = callExpectingError(createDataset, Map.of());
		assertThat(message).contains("session");
	}

	private static Map<String, Object> args(String datasetId, String objectId, String feature, String action, Object value) {
		Map<String, Object> arguments = new HashMap<>();
		arguments.put("datasetId", datasetId);
		arguments.put("objectId", objectId);
		arguments.put("feature", feature);
		arguments.put("action", action);
		arguments.put("value", value);
		return arguments;
	}

	private static void setField(Object target, String fieldName, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
