package de.metanome.algorithms.aindd.config;

import de.metanome.algorithm_integration.input.RelationalInputGenerator;
import de.metanome.algorithm_integration.input.TableInputGenerator;
import de.metanome.algorithm_integration.result_receiver.InclusionDependencyResultReceiver;

import java.beans.ConstructorProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @projectName: AproINDAlgo
 * @package: de.metanome.algorithms.aindd.config
 * @className: Configuration
 * @author: SuQingdong
 * @description: 0223 为缩短文件读取时间 向demarchi靠近 借鉴demarchi的文件读取
 * @date: 2024/2/23 16:25
 * @version: 1.0
 */
public class Configuration {
    private List<TableInputGenerator> tableInputGenerators;
    private List<RelationalInputGenerator> relationalInputGenerators;
    private boolean processEmptyColumns;
    private int inputRowLimit;
    private InclusionDependencyResultReceiver resultReceiver;

    public static Configuration withDefaults() {
        return builder().tableInputGenerators(Collections.emptyList()).relationalInputGenerators(Collections.emptyList()).processEmptyColumns(true).inputRowLimit(-1).resultReceiver((InclusionDependencyResultReceiver)null).build();
    }

    public static ConfigurationBuilder builder() {
        return new ConfigurationBuilder();
    }

    public List<TableInputGenerator> getTableInputGenerators() {
        return this.tableInputGenerators;
    }

    public List<RelationalInputGenerator> getRelationalInputGenerators() {
        return this.relationalInputGenerators;
    }

    public boolean isProcessEmptyColumns() {
        return this.processEmptyColumns;
    }

    public int getInputRowLimit() {
        return this.inputRowLimit;
    }

    public InclusionDependencyResultReceiver getResultReceiver() {
        return this.resultReceiver;
    }

    public void setTableInputGenerators(List<TableInputGenerator> tableInputGenerators) {
        this.tableInputGenerators = tableInputGenerators;
    }

    public void setRelationalInputGenerators(List<RelationalInputGenerator> relationalInputGenerators) {
        this.relationalInputGenerators = relationalInputGenerators;
    }

    public void setProcessEmptyColumns(boolean processEmptyColumns) {
        this.processEmptyColumns = processEmptyColumns;
    }

    public void setInputRowLimit(int inputRowLimit) {
        this.inputRowLimit = inputRowLimit;
    }

    public void setResultReceiver(InclusionDependencyResultReceiver resultReceiver) {
        this.resultReceiver = resultReceiver;
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        } else if (!(o instanceof Configuration)) {
            return false;
        } else {
            Configuration other = (Configuration)o;
            if (!other.canEqual(this)) {
                return false;
            } else {
                label55: {
                    Object this$tableInputGenerators = this.getTableInputGenerators();
                    Object other$tableInputGenerators = other.getTableInputGenerators();
                    if (this$tableInputGenerators == null) {
                        if (other$tableInputGenerators == null) {
                            break label55;
                        }
                    } else if (this$tableInputGenerators.equals(other$tableInputGenerators)) {
                        break label55;
                    }

                    return false;
                }

                Object this$relationalInputGenerators = this.getRelationalInputGenerators();
                Object other$relationalInputGenerators = other.getRelationalInputGenerators();
                if (this$relationalInputGenerators == null) {
                    if (other$relationalInputGenerators != null) {
                        return false;
                    }
                } else if (!this$relationalInputGenerators.equals(other$relationalInputGenerators)) {
                    return false;
                }

                if (this.isProcessEmptyColumns() != other.isProcessEmptyColumns()) {
                    return false;
                } else if (this.getInputRowLimit() != other.getInputRowLimit()) {
                    return false;
                } else {
                    Object this$resultReceiver = this.getResultReceiver();
                    Object other$resultReceiver = other.getResultReceiver();
                    if (this$resultReceiver == null) {
                        if (other$resultReceiver != null) {
                            return false;
                        }
                    } else if (!this$resultReceiver.equals(other$resultReceiver)) {
                        return false;
                    }

                    return true;
                }
            }
        }
    }

    protected boolean canEqual(Object other) {
        return other instanceof Configuration;
    }

    public int hashCode() {
        //int PRIME = true;
        //0223
        int result = 1;
        Object $tableInputGenerators = this.getTableInputGenerators();
        result = result * 59 + ($tableInputGenerators == null ? 43 : $tableInputGenerators.hashCode());
        Object $relationalInputGenerators = this.getRelationalInputGenerators();
        result = result * 59 + ($relationalInputGenerators == null ? 43 : $relationalInputGenerators.hashCode());
        result = result * 59 + (this.isProcessEmptyColumns() ? 79 : 97);
        result = result * 59 + this.getInputRowLimit();
        Object $resultReceiver = this.getResultReceiver();
        result = result * 59 + ($resultReceiver == null ? 43 : $resultReceiver.hashCode());
        return result;
    }

    public String toString() {
        return "Configuration(tableInputGenerators=" + this.getTableInputGenerators() + ", relationalInputGenerators=" + this.getRelationalInputGenerators() + ", processEmptyColumns=" + this.isProcessEmptyColumns() + ", inputRowLimit=" + this.getInputRowLimit() + ", resultReceiver=" + this.getResultReceiver() + ")";
    }

    public Configuration() {
    }

    @ConstructorProperties({"tableInputGenerators", "relationalInputGenerators", "processEmptyColumns", "inputRowLimit", "resultReceiver"})
    public Configuration(List<TableInputGenerator> tableInputGenerators, List<RelationalInputGenerator> relationalInputGenerators, boolean processEmptyColumns, int inputRowLimit, InclusionDependencyResultReceiver resultReceiver) {
        this.tableInputGenerators = tableInputGenerators;
        this.relationalInputGenerators = relationalInputGenerators;
        this.processEmptyColumns = processEmptyColumns;
        this.inputRowLimit = inputRowLimit;
        this.resultReceiver = resultReceiver;
    }

    public static class ConfigurationBuilder {
        private ArrayList<TableInputGenerator> tableInputGenerators;
        private ArrayList<RelationalInputGenerator> relationalInputGenerators;
        private boolean processEmptyColumns;
        private int inputRowLimit;
        private InclusionDependencyResultReceiver resultReceiver;

        ConfigurationBuilder() {
        }

        public ConfigurationBuilder tableInputGenerator(TableInputGenerator tableInputGenerator) {
            if (this.tableInputGenerators == null) {
                this.tableInputGenerators = new ArrayList();
            }

            this.tableInputGenerators.add(tableInputGenerator);
            return this;
        }

        public ConfigurationBuilder tableInputGenerators(Collection<? extends TableInputGenerator> tableInputGenerators) {
            if (this.tableInputGenerators == null) {
                this.tableInputGenerators = new ArrayList();
            }

            this.tableInputGenerators.addAll(tableInputGenerators);
            return this;
        }

        public ConfigurationBuilder clearTableInputGenerators() {
            if (this.tableInputGenerators != null) {
                this.tableInputGenerators.clear();
            }

            return this;
        }

        public ConfigurationBuilder relationalInputGenerator(RelationalInputGenerator relationalInputGenerator) {
            if (this.relationalInputGenerators == null) {
                this.relationalInputGenerators = new ArrayList();
            }

            this.relationalInputGenerators.add(relationalInputGenerator);
            return this;
        }

        public ConfigurationBuilder relationalInputGenerators(Collection<? extends RelationalInputGenerator> relationalInputGenerators) {
            if (this.relationalInputGenerators == null) {
                this.relationalInputGenerators = new ArrayList();
            }

            this.relationalInputGenerators.addAll(relationalInputGenerators);
            return this;
        }

        public ConfigurationBuilder clearRelationalInputGenerators() {
            if (this.relationalInputGenerators != null) {
                this.relationalInputGenerators.clear();
            }

            return this;
        }

        public ConfigurationBuilder processEmptyColumns(boolean processEmptyColumns) {
            this.processEmptyColumns = processEmptyColumns;
            return this;
        }

        public ConfigurationBuilder inputRowLimit(int inputRowLimit) {
            this.inputRowLimit = inputRowLimit;
            return this;
        }

        public ConfigurationBuilder resultReceiver(InclusionDependencyResultReceiver resultReceiver) {
            this.resultReceiver = resultReceiver;
            return this;
        }

        public Configuration build() {
            List tableInputGenerators;
            switch (this.tableInputGenerators == null ? 0 : this.tableInputGenerators.size()) {
                case 0:
                    tableInputGenerators = Collections.emptyList();
                    break;
                case 1:
                    tableInputGenerators = Collections.singletonList(this.tableInputGenerators.get(0));
                    break;
                default:
                    tableInputGenerators = Collections.unmodifiableList(new ArrayList(this.tableInputGenerators));
            }

            List relationalInputGenerators;
            switch (this.relationalInputGenerators == null ? 0 : this.relationalInputGenerators.size()) {
                case 0:
                    relationalInputGenerators = Collections.emptyList();
                    break;
                case 1:
                    relationalInputGenerators = Collections.singletonList(this.relationalInputGenerators.get(0));
                    break;
                default:
                    relationalInputGenerators = Collections.unmodifiableList(new ArrayList(this.relationalInputGenerators));
            }

            return new Configuration(tableInputGenerators, relationalInputGenerators, this.processEmptyColumns, this.inputRowLimit, this.resultReceiver);
        }

        public String toString() {
            return "Configuration.ConfigurationBuilder(tableInputGenerators=" + this.tableInputGenerators + ", relationalInputGenerators=" + this.relationalInputGenerators + ", processEmptyColumns=" + this.processEmptyColumns + ", inputRowLimit=" + this.inputRowLimit + ", resultReceiver=" + this.resultReceiver + ")";
        }
    }
}
