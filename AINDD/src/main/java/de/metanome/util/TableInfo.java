package de.metanome.util;

/**
 * @projectName: AproINDAlgo
 * @package: de.metanome.util
 * @className: TableInfo
 * @author: SuQingdong
 * @description: TableInfo
 * @date: 2024/2/23 16:32
 * @version: 1.0
 */

import de.metanome.algorithm_integration.input.RelationalInputGenerator;
import de.metanome.algorithm_integration.input.TableInputGenerator;
import java.beans.ConstructorProperties;
import java.util.List;

public class TableInfo {
    private final String tableName;
    private final RelationalInputGenerator relationalInputGenerator;
    private final TableInputGenerator tableInputGenerator;
    private final List<String> columnNames;
    private final List<String> columnTypes;

    public List<String> getColumnTypes() {
        return this.columnTypes;
    }

    public String getTableName() {
        return this.tableName;
    }

    public RelationalInputGenerator getRelationalInputGenerator() {
        return this.relationalInputGenerator;
    }

    public List<String> getColumnNames() {
        return this.columnNames;
    }

    public TableInputGenerator getTableInputGenerator() {
        return this.tableInputGenerator;
    }

    public int getColumnCount() {
        return this.columnNames.size();
    }

    public RelationalInputGenerator selectInputGenerator() {
        return (RelationalInputGenerator)(this.relationalInputGenerator == null ? this.tableInputGenerator : this.relationalInputGenerator);
    }

    @ConstructorProperties({"tableName", "relationalInputGenerator", "tableInputGenerator", "columnNames", "columnTypes"})
    TableInfo(String tableName, RelationalInputGenerator relationalInputGenerator, TableInputGenerator tableInputGenerator, List<String> columnNames, List<String> columnTypes) {
        this.tableName = tableName;
        this.relationalInputGenerator = relationalInputGenerator;
        this.tableInputGenerator = tableInputGenerator;
        this.columnNames = columnNames;
        this.columnTypes = columnTypes;
    }

    public static TableInfoBuilder builder() {
        return new TableInfoBuilder();
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        } else if (!(o instanceof TableInfo)) {
            return false;
        } else {
            TableInfo other = (TableInfo)o;
            if (!other.canEqual(this)) {
                return false;
            } else {
                label71: {
                    Object this$tableName = this.getTableName();
                    Object other$tableName = other.getTableName();
                    if (this$tableName == null) {
                        if (other$tableName == null) {
                            break label71;
                        }
                    } else if (this$tableName.equals(other$tableName)) {
                        break label71;
                    }

                    return false;
                }

                Object this$relationalInputGenerator = this.getRelationalInputGenerator();
                Object other$relationalInputGenerator = other.getRelationalInputGenerator();
                if (this$relationalInputGenerator == null) {
                    if (other$relationalInputGenerator != null) {
                        return false;
                    }
                } else if (!this$relationalInputGenerator.equals(other$relationalInputGenerator)) {
                    return false;
                }

                label57: {
                    Object this$tableInputGenerator = this.getTableInputGenerator();
                    Object other$tableInputGenerator = other.getTableInputGenerator();
                    if (this$tableInputGenerator == null) {
                        if (other$tableInputGenerator == null) {
                            break label57;
                        }
                    } else if (this$tableInputGenerator.equals(other$tableInputGenerator)) {
                        break label57;
                    }

                    return false;
                }

                Object this$columnNames = this.getColumnNames();
                Object other$columnNames = other.getColumnNames();
                if (this$columnNames == null) {
                    if (other$columnNames != null) {
                        return false;
                    }
                } else if (!this$columnNames.equals(other$columnNames)) {
                    return false;
                }

                Object this$columnTypes = this.getColumnTypes();
                Object other$columnTypes = other.getColumnTypes();
                if (this$columnTypes == null) {
                    if (other$columnTypes == null) {
                        return true;
                    }
                } else if (this$columnTypes.equals(other$columnTypes)) {
                    return true;
                }

                return false;
            }
        }
    }

    protected boolean canEqual(Object other) {
        return other instanceof TableInfo;
    }

    public int hashCode() {
        //int PRIME = true;
        int result = 1;
        Object $tableName = this.getTableName();
        result = result * 59 + ($tableName == null ? 43 : $tableName.hashCode());
        Object $relationalInputGenerator = this.getRelationalInputGenerator();
        result = result * 59 + ($relationalInputGenerator == null ? 43 : $relationalInputGenerator.hashCode());
        Object $tableInputGenerator = this.getTableInputGenerator();
        result = result * 59 + ($tableInputGenerator == null ? 43 : $tableInputGenerator.hashCode());
        Object $columnNames = this.getColumnNames();
        result = result * 59 + ($columnNames == null ? 43 : $columnNames.hashCode());
        Object $columnTypes = this.getColumnTypes();
        result = result * 59 + ($columnTypes == null ? 43 : $columnTypes.hashCode());
        return result;
    }

    public String toString() {
        return "TableInfo(tableName=" + this.getTableName() + ", relationalInputGenerator=" + this.getRelationalInputGenerator() + ", tableInputGenerator=" + this.getTableInputGenerator() + ", columnNames=" + this.getColumnNames() + ", columnTypes=" + this.getColumnTypes() + ")";
    }

    public static class TableInfoBuilder {
        private String tableName;
        private RelationalInputGenerator relationalInputGenerator;
        private TableInputGenerator tableInputGenerator;
        private List<String> columnNames;
        private List<String> columnTypes;

        TableInfoBuilder() {
        }

        public TableInfoBuilder tableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        public TableInfoBuilder relationalInputGenerator(RelationalInputGenerator relationalInputGenerator) {
            this.relationalInputGenerator = relationalInputGenerator;
            return this;
        }

        public TableInfoBuilder tableInputGenerator(TableInputGenerator tableInputGenerator) {
            this.tableInputGenerator = tableInputGenerator;
            return this;
        }

        public TableInfoBuilder columnNames(List<String> columnNames) {
            this.columnNames = columnNames;
            return this;
        }

        public TableInfoBuilder columnTypes(List<String> columnTypes) {
            this.columnTypes = columnTypes;
            return this;
        }

        public TableInfo build() {
            return new TableInfo(this.tableName, this.relationalInputGenerator, this.tableInputGenerator, this.columnNames, this.columnTypes);
        }

        public String toString() {
            return "TableInfo.TableInfoBuilder(tableName=" + this.tableName + ", relationalInputGenerator=" + this.relationalInputGenerator + ", tableInputGenerator=" + this.tableInputGenerator + ", columnNames=" + this.columnNames + ", columnTypes=" + this.columnTypes + ")";
        }
    }
}
