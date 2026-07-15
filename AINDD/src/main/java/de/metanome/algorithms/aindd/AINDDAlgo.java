package de.metanome.algorithms.aindd;

import java.sql.SQLException;
import java.util.ArrayList;
import de.metanome.algorithm_integration.AlgorithmConfigurationException;
import de.metanome.algorithm_integration.AlgorithmExecutionException;
import de.metanome.algorithm_integration.algorithm_types.*;
import de.metanome.algorithm_integration.configuration.ConfigurationRequirement;
import de.metanome.algorithm_integration.configuration.ConfigurationRequirementBoolean;
import de.metanome.algorithm_integration.configuration.ConfigurationRequirementInteger;
import de.metanome.algorithm_integration.configuration.ConfigurationRequirementRelationalInput;
import de.metanome.algorithm_integration.configuration.ConfigurationRequirementString;
import de.metanome.algorithm_integration.input.InputGenerationException;
import de.metanome.algorithm_integration.input.RelationalInput;
import de.metanome.algorithm_integration.input.RelationalInputGenerator;
import de.metanome.algorithm_integration.result_receiver.InclusionDependencyResultReceiver;
import de.metanome.algorithms.aindd.config.Configuration;
import de.uni_potsdam.utils.CollectionUtils;
import de.uni_potsdam.utils.FileUtils;
import java.io.File;
import java.util.Arrays;


public class AINDDAlgo extends AINDDAlgorithm implements InclusionDependencyAlgorithm,RelationalInputParameterAlgorithm,StringParameterAlgorithm, IntegerParameterAlgorithm, BooleanParameterAlgorithm {
	private final Configuration defaultValues = Configuration.withDefaults();
	private final Configuration.ConfigurationBuilder builder = Configuration.builder();

	public ArrayList<ConfigurationRequirement<?>> getConfigurationRequirements() {
		ArrayList<ConfigurationRequirement<?>> configs = new ArrayList(5);
		configs.add(new ConfigurationRequirementRelationalInput(AINDDAlgo.Identifier.INPUT_FILES.name(), -1));

		ConfigurationRequirementString tempFolder = new ConfigurationRequirementString(AINDDAlgo.Identifier.TEMP_FOLDER_PATH.name());
		String[] defaultTempFolder = new String[]{this.tempFolderPath};
		tempFolder.setDefaultValues(defaultTempFolder);
		tempFolder.setRequired(true);
		configs.add(tempFolder);

		ConfigurationRequirementInteger inputRowLimit = new ConfigurationRequirementInteger(AINDDAlgo.Identifier.INPUT_ROW_LIMIT.name());
		Integer[] defaultInputRowLimit = new Integer[]{this.inputRowLimit};
		inputRowLimit.setDefaultValues(defaultInputRowLimit);
		inputRowLimit.setRequired(false);
		configs.add(inputRowLimit);

		ConfigurationRequirementInteger numBucketsPerColumn = new ConfigurationRequirementInteger(AINDDAlgo.Identifier.NUM_BUCKETS_PER_COLUMN.name());
		Integer[] defaultNumBucketsPerColumn = new Integer[]{this.numPartitionsPerColumn};
		numBucketsPerColumn.setDefaultValues(defaultNumBucketsPerColumn);
		numBucketsPerColumn.setRequired(true);
		configs.add(numBucketsPerColumn);

		ConfigurationRequirementInteger maxMemoryUsagePercentage = new ConfigurationRequirementInteger(Identifier.MAX_MEMORY_USAGE_PERCENTAGE_UP.name());
		Integer[] defaultMaxMemoryUsagePercentage = new Integer[]{this.maxMemoryUsagePercentageUp};
		maxMemoryUsagePercentage.setDefaultValues(defaultMaxMemoryUsagePercentage);
		maxMemoryUsagePercentage.setRequired(true);
		configs.add(maxMemoryUsagePercentage);

		ConfigurationRequirementInteger maxMemoryUsagePercentageLow = new ConfigurationRequirementInteger(Identifier.MAX_MEMORY_USAGE_PERCENTAGE_LOW.name());
		Integer[] maxMemoryUsagePercentageLow_2 = new Integer[]{this.maxMemoryUsagePercentageLow};
		maxMemoryUsagePercentageLow.setDefaultValues(maxMemoryUsagePercentageLow_2);
		maxMemoryUsagePercentageLow.setRequired(true);
		configs.add(maxMemoryUsagePercentageLow);

		ConfigurationRequirementInteger filtersize = new ConfigurationRequirementInteger(Identifier.FILTER_SIZE.name());
		Integer[] filtersize2 = new Integer[]{this.filterSize};
		filtersize.setDefaultValues(filtersize2);
		filtersize.setRequired(true);
		configs.add(filtersize);

		ConfigurationRequirementInteger violate_rate = new ConfigurationRequirementInteger(Identifier.VIOLATE_PER_10000.name());
		Integer[] violateRate = new Integer[]{this.violate_per};
		violate_rate.setDefaultValues(violateRate);
		violate_rate.setRequired(true);
		configs.add(violate_rate);

		ConfigurationRequirementBoolean cleanTemp = new ConfigurationRequirementBoolean(AINDDAlgo.Identifier.CLEAN_TEMP.name());
		Boolean[] defaultCleanTemp = new Boolean[]{this.cleanTemp};
		cleanTemp.setDefaultValues(defaultCleanTemp);
		cleanTemp.setRequired(true);
		configs.add(cleanTemp);

		ConfigurationRequirementBoolean deleteMode = new ConfigurationRequirementBoolean(Identifier.DELETE_MODE.name());
		Boolean[] defaultDeleteTemp = new Boolean[]{this.deleteMode};
		deleteMode.setDefaultValues(defaultDeleteTemp);
		deleteMode.setRequired(true);
		configs.add(deleteMode);

		return configs;
	}

	public void setRelationalInputConfigurationValue(String identifier, RelationalInputGenerator... values) throws AlgorithmConfigurationException {
		if (AINDDAlgo.Identifier.INPUT_FILES.name().equals(identifier)) {
			this.tableNames = new String[values.length];
			this.builder.relationalInputGenerators(Arrays.asList(values));
			RelationalInput input = null;
			for(int i = 0; i < values.length; ++i) {
				try {
					input = values[i].generateNewCopy();
					this.tableNames[i] = input.relationName();
				} catch (InputGenerationException var9) {
					throw new AlgorithmConfigurationException(var9.getMessage());
				} finally {
					FileUtils.close(input);
				}
			}
		} else {
			this.handleUnknownConfiguration(identifier, CollectionUtils.concat(values, ","));
		}

	}

	public void setResultReceiver(InclusionDependencyResultReceiver resultReceiver) {
		this.resultReceiver = resultReceiver;
	}

	public void setIntegerConfigurationValue(String identifier, Integer... values) throws AlgorithmConfigurationException {
		if (AINDDAlgo.Identifier.INPUT_ROW_LIMIT.name().equals(identifier)) {
			if (values.length > 0) {
				this.inputRowLimit = values[0];
			}
		} else if (AINDDAlgo.Identifier.NUM_BUCKETS_PER_COLUMN.name().equals(identifier)) {
			if (values[0] <= 0) {
				throw new AlgorithmConfigurationException(AINDDAlgo.Identifier.NUM_BUCKETS_PER_COLUMN.name() + " must be greater than 0!");
			}
			this.numPartitionsPerColumn = values[0];
		}
		else if (AINDDAlgo.Identifier.FILTER_SIZE.name().equals(identifier)) {
			if (values[0] <= 0) {
				throw new AlgorithmConfigurationException(AINDDAlgo.Identifier.FILTER_SIZE.name() + " must be greater than 0!");
			}
			this.filterSize = values[0];
		}
		else if (Identifier.MAX_MEMORY_USAGE_PERCENTAGE_UP.name().equals(identifier)) {
			if (values[0] <= 0) {
				throw new AlgorithmConfigurationException(Identifier.MAX_MEMORY_USAGE_PERCENTAGE_UP.name() + " must be greater than 0!");
			}

			this.maxMemoryUsagePercentageUp = values[0];
		}
		else if (Identifier.MAX_MEMORY_USAGE_PERCENTAGE_LOW.name().equals(identifier)) {
			if (values[0] <= 0) {
				throw new AlgorithmConfigurationException(Identifier.MAX_MEMORY_USAGE_PERCENTAGE_LOW.name() + " must be greater than 0!");
			}
			this.maxMemoryUsagePercentageLow = values[0];
		}
		else if (AINDDAlgo.Identifier.VIOLATE_PER_10000.name().equals(identifier)) {
			if (values[0] < 0) {
				throw new AlgorithmConfigurationException(AINDDAlgo.Identifier.VIOLATE_PER_10000.name() + " must be greater than 0!");
			}
			this.violate_per = values[0];
		}
		else {
			this.handleUnknownConfiguration(identifier, CollectionUtils.concat(values, ","));
		}
	}

	public void setStringConfigurationValue(String identifier, String... values) throws AlgorithmConfigurationException {
		if (AINDDAlgo.Identifier.TEMP_FOLDER_PATH.name().equals(identifier)) {
			if ("".equals(values[0]) || " ".equals(values[0]) || "/".equals(values[0]) || "\\".equals(values[0]) || File.separator.equals(values[0]) || FileUtils.isRoot(new File(values[0]))) {
				throw new AlgorithmConfigurationException(AINDDAlgo.Identifier.TEMP_FOLDER_PATH + " must not be \"" + values[0] + "\"");
			}

			this.tempFolderPath = values[0];
		} else {
			this.handleUnknownConfiguration(identifier, CollectionUtils.concat(values, ","));
		}

	}

	public void setBooleanConfigurationValue(String identifier, Boolean... values) throws AlgorithmConfigurationException {
		if (AINDDAlgo.Identifier.CLEAN_TEMP.name().equals(identifier)) {
			this.cleanTemp = values[0];
		} else if (Identifier.DELETE_MODE.name().equals(identifier)) {
			this.deleteMode = values[0];
		}else {
			this.handleUnknownConfiguration(identifier, CollectionUtils.concat(values, ","));
		}

	}

	protected void handleUnknownConfiguration(String identifier, String value) throws AlgorithmConfigurationException {
		throw new AlgorithmConfigurationException("Unknown configuration: " + identifier + " -> " + value);
	}

	public void execute()  {
		try{
			Configuration configuration = this.builder.build();
			super.execute(configuration);
		}catch (AlgorithmExecutionException e) {
			throw new RuntimeException(e);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	public String getAuthors() {
		return this.getAuthorName();
	}

	public String getDescription() {
		return this.getDescriptionText();
	}

	public static enum Identifier {
		INPUT_FILES,
		INPUT_ROW_LIMIT,
		TEMP_FOLDER_PATH,
		CLEAN_TEMP,
		//DETECT_NARY,
		//MAX_NARY_LEVEL,
		NUM_BUCKETS_PER_COLUMN,
		FILTER_SIZE,
		VIOLATE_PER_10000,
		DELETE_MODE,
		//MEMORY_CHECK_FREQUENCY,
		MAX_MEMORY_USAGE_PERCENTAGE_UP,
		MAX_MEMORY_USAGE_PERCENTAGE_LOW;
		private Identifier() {
		}
	}
}
