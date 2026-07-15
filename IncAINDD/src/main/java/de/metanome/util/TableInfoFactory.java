package de.metanome.util;

import de.metanome.algorithm_integration.AlgorithmConfigurationException;
import de.metanome.algorithm_integration.input.InputGenerationException;
import de.metanome.algorithm_integration.input.RelationalInput;
import de.metanome.algorithm_integration.input.RelationalInputGenerator;
import de.metanome.algorithm_integration.input.TableInputGenerator;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
/**
 * @projectName: AproINDAlgo
 * @package: de.metanome.util
 * @className: TableInfoFactory
 * @author: SuQingdong
 * @description: TableInfoFactory
 * @date: 2024/2/23 16:31
 * @version: 1.0
 */



public class TableInfoFactory {
    private static final String STRING_COLUMN_TYE = "String";

    public TableInfoFactory() {
    }

    public List<TableInfo> create(Collection<RelationalInputGenerator> relationalInputGenerators, Collection<TableInputGenerator> tableInputGenerators) throws InputGenerationException, AlgorithmConfigurationException {
        List<RelationalInputGenerator> relational = new ArrayList();
        List<TableInputGenerator> table = new ArrayList();
        if (tableInputGenerators != null) {
            table.addAll(tableInputGenerators);
        }

        if (relationalInputGenerators != null) {
            Iterator var5 = relationalInputGenerators.iterator();

            while(var5.hasNext()) {
                RelationalInputGenerator generator = (RelationalInputGenerator)var5.next();
                if (generator instanceof TableInputGenerator) {
                    table.add((TableInputGenerator)generator);
                } else {
                    relational.add(generator);
                }
            }
        }

        List<TableInfo> tables = new ArrayList();
        tables.addAll(this.createFromRelationalInputs(relational));
        tables.addAll(this.createFromTableInputs(table));
        return tables;
    }

    public List<TableInfo> createFromRelationalInputs(Collection<RelationalInputGenerator> generators) throws InputGenerationException {
        List<TableInfo> result = new ArrayList();
        if (generators != null) {
            Iterator var3 = generators.iterator();

            while(var3.hasNext()) {
                RelationalInputGenerator generator = (RelationalInputGenerator)var3.next();

                try {
                    RelationalInput input = generator.generateNewCopy();
                    Throwable var6 = null;

                    try {
                        result.add(this.createFrom(generator, input));
                    } catch (Throwable var16) {
                        var6 = var16;
                        throw var16;
                    } finally {
                        if (input != null) {
                            if (var6 != null) {
                                try {
                                    input.close();
                                } catch (Throwable var15) {
                                    var6.addSuppressed(var15);
                                }
                            } else {
                                input.close();
                            }
                        }

                    }
                } catch (Exception var18) {
                    throw new InputGenerationException("relational input", var18);
                }
            }
        }

        return result;
    }

    private TableInfo createFrom(RelationalInputGenerator generator, RelationalInput input) {
        return TableInfo.builder().relationalInputGenerator(generator).tableName(input.relationName()).columnNames(input.columnNames()).columnTypes(this.getFixedColumnTypes(input)).build();
    }

    private List<String> getFixedColumnTypes(RelationalInput input) {
        String[] types = new String[input.numberOfColumns()];
        Arrays.setAll(types, (index) -> {
            return "String";
        });
        return Arrays.asList(types);
    }

    public List<TableInfo> createFromTableInputs(Collection<TableInputGenerator> generators) throws InputGenerationException, AlgorithmConfigurationException {
        List<TableInfo> result = new ArrayList();
        if (generators != null) {
            Iterator var3 = generators.iterator();

            while(var3.hasNext()) {
                TableInputGenerator generator = (TableInputGenerator)var3.next();
                result.add(this.createFrom(generator));
            }
        }

        return result;
    }

    private TableInfo createFrom(TableInputGenerator generator) throws AlgorithmConfigurationException, InputGenerationException {
        try {
            ResultSet set = generator.select();
            Throwable var3 = null;

            try {
                ResultSetMetaData metadata = set.getMetaData();
                List<String> columnNames = new ArrayList();
                List<String> columnTypes = new ArrayList();

                for(int index = 1; index <= metadata.getColumnCount(); ++index) {
                    columnNames.add(metadata.getColumnName(index));
                    columnTypes.add(metadata.getColumnTypeName(index));
                }

                TableInfo var34 = TableInfo.builder().tableInputGenerator(generator).tableName(metadata.getTableName(1)).columnNames(columnNames).columnTypes(columnTypes).build();
                return var34;
            } catch (Throwable var30) {
                var3 = var30;
                throw var30;
            } finally {
                if (set != null) {
                    if (var3 != null) {
                        try {
                            set.close();
                        } catch (Throwable var29) {
                            var3.addSuppressed(var29);
                        }
                    } else {
                        set.close();
                    }
                }

            }
        } catch (SQLException var32) {
            throw new InputGenerationException("database error while reading metadata", var32);
        } finally {
            try {
                generator.close();
            } catch (Exception var28) {
                throw new InputGenerationException("terrible", var28);
            }
        }
    }
}