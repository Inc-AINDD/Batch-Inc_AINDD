package de.metanome.algorithms.aindd;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.sql.SQLException;
import java.util.*;
import de.metanome.algorithm_integration.AlgorithmConfigurationException;
import de.metanome.algorithm_integration.AlgorithmExecutionException;
import de.metanome.algorithm_integration.ColumnIdentifier;
import de.metanome.algorithm_integration.ColumnPermutation;
import de.metanome.algorithm_integration.input.*;
import de.metanome.algorithm_integration.result_receiver.ColumnNameMismatchException;
import de.metanome.algorithm_integration.result_receiver.CouldNotReceiveResultException;
import de.metanome.algorithm_integration.result_receiver.InclusionDependencyResultReceiver;
import de.metanome.algorithm_integration.results.InclusionDependency;
import de.metanome.algorithms.aindd.config.Configuration;
import de.metanome.algorithms.aindd.io.DiskBack;
import de.metanome.algorithms.aindd.structures.*;
import de.metanome.util.TableInfo;
import de.metanome.util.TableInfoFactory;
import de.uni_potsdam.utils.FileUtils;
import de.metanome.algorithms.aindd.io.FilterDiskBack;

public class AINDDAlgorithm {
    protected InclusionDependencyResultReceiver resultReceiver = null;
    protected List<String> columnNames;
    private TableInfoFactory tableInfoFactory;
    protected Configuration configuration;
    protected String[] tableNames = null;
    protected String tempFolderPath = "AINDD_temp";
    protected boolean cleanTemp = true;
    protected boolean deleteMode = false;
    protected boolean dataDeletion = false;
    protected boolean dataInsertion = false;
    protected int inputRowLimit = -1;
    protected int numPartitionsPerColumn = 10;
    private int numColumns;
    private File tempFolder = null;
    private int[] tableColumnStartIndexes = null;
    private long availableMemory;
    protected int maxMemoryUsagePercentageUp = 80;
    protected int maxMemoryUsagePercentageLow = 60;
    private long maxMemoryUsage;
    private long maxMemoryUsageLow;
    protected int memoryCheckFrequency = 100;
    private DiskBack diskBack;
    protected List<List<Set<String>>> partitions_list = null;
    protected List<List<int[]>> bucketSizeRange;
    protected List<List<Boolean>> partition_backed = null;
    protected PriorityQueue<PartitionSize> maxPartitionHeap = null;
    protected PriorityQueue<PartitionSize> maxPartitionHeapCurr = null;
    protected int filterSize = 1024;
    protected int violate_per;
    protected int save2disk_batch = 10;
    protected int microProbingThreshold = 1000;
    protected List<List<ThreeLayersFilter>> filterInsert_list = null;
    protected boolean[] isNotFullMem;
    protected boolean[] isMemAndBuild;

    protected int[][] violationMatrix;
    protected Map<String, Integer> lineNameToIndex;
    protected int[] rowVioNum;
    protected int[] lineVioNum;
    protected boolean[] leftViolation;
    protected boolean[] rightViolation;
    protected boolean[] violation;
    protected int[][] columnThreshold;
    protected Integer[] bucketInMemPerPart;
    protected FilterDiskBack filterDiskBack;
    protected int[][] matrec;
    protected long[][] totalVioMatrix;

    // things for deletion semantics
    protected List<List<ThreeLayersFilterDelete>> filterDelete_list = null;
    protected int[] rowCountOfColumn;
    protected List<List<HashMap<String, Integer>>> partitions_delete = null;

    public void execute(Configuration configuration) throws AlgorithmExecutionException, SQLException {

        this.configuration = configuration;
        this.tableInfoFactory = new TableInfoFactory();

        List<TableInfo> tables = this.tableInfoFactory.create(
                configuration.getRelationalInputGenerators(),
                configuration.getTableInputGenerators());
        if (tables.size() > 1) {
            tables = tables.subList(0, 1);
            if (this.tableNames != null && this.tableNames.length > 1) {
                this.tableNames = new String[] { this.tableNames[0] };
            }
        }
        // ===== common initialization =====
        this.initializeCommon(tables);

        if (!this.deleteMode) {
            if(!dataDeletion && !dataInsertion) {
                // ========== Phase 1: initializeInsertion ==========
                this.initializeInsertion(tables);

                // ========== Phase 2: partitioningInsertion ==========
                this.partitioningInsertion(tables);

                // ========== Phase 3: AIND discovery ==========
                List<InclusionDependency> results;
                try {
                    results = this.AINDDiscovery();
                } catch (ClassNotFoundException e) {
                    throw new AlgorithmExecutionException("AINDDiscovery failed", e);
                }

                // ========== Phase 4: Data to Disk ==========
                this.cleanupOldLayer3();
                try {
                    this.rebuildLayer3(tables);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                this.emit(results);

                String contextFilePath = this.tempFolderPath + File.separator + "aindd_context.ser";
                this.saveContextToDisk(contextFilePath,false);

                this.partitions_list = null;
                this.filterInsert_list = null;
                this.partitions_delete = null;
                this.filterDelete_list = null;
                this.maxPartitionHeap = null;
                this.maxPartitionHeapCurr = null;

                System.gc();
            }

            else {
                try {
                    IncrementalStatsAlgorithm incStats = new IncrementalStatsAlgorithm();
                    incStats.loadContextFromDisk("AINDD_temp2/aindd_context.ser");
                    incStats.resultReceiver = this.resultReceiver;
                    incStats.dataDeletion = this.dataDeletion;
                    incStats.dataInsertion = this.dataInsertion;
                    incStats.execute(this.configuration);
                } catch (Exception e) {
                    throw new AlgorithmExecutionException("Incremental stats processing failed", e);
                }
            }
        } else {
            if(!dataDeletion && !dataInsertion){
                // ========== Phase 1: initializeDeletion ==========
                this.initializeDeletion(tables);

                // ========== Phase 2: partitioningDeletion ==========
                this.partitioningDeletion(tables);

                // ========== Phase 3: AIND discovery (deletion) ==========
                List<InclusionDependency> results;
                try {
                    results = this.AINDDiscoveryDeletion();
                } catch (ClassNotFoundException e) {
                    throw new AlgorithmExecutionException("AINDDiscoveryDeletion failed", e);
                }
                // ========== Phase 4: Data to Disk ==========
                this.cleanupOldLayer3();
                try {
                    this.rebuildLayer3Deletion(tables);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                this.emit(results);

                String contextFilePath = this.tempFolderPath + File.separator + "aindd_context.ser";
                this.saveContextToDisk(contextFilePath, true);

                this.partitions_delete = null;
                this.filterDelete_list = null;
                this.maxPartitionHeap = null;
                this.maxPartitionHeapCurr = null;
                System.gc();
            }

            else {
                try {
                    IncrementalStatsAlgorithmDeletion incStats = new IncrementalStatsAlgorithmDeletion();
                    incStats.loadContextFromDisk("AINDD_temp2/aindd_context.ser");
                    incStats.resultReceiver = this.resultReceiver;
                    incStats.dataDeletion = this.dataDeletion;
                    incStats.dataInsertion = this.dataInsertion;
                    incStats.execute(this.configuration);
                } catch (Exception e) {
                    throw new AlgorithmExecutionException("Incremental stats processing failed", e);
                }
            }
        }
    }

    public void saveContextToDisk(String contextFilePath, boolean isDeletionMode) {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(contextFilePath))) {
            oos.writeObject(this.columnNames);
            oos.writeInt(this.numColumns);
            oos.writeObject(this.matrec);
            oos.writeObject(this.tableNames);
            oos.writeInt(this.numPartitionsPerColumn);
            oos.writeInt(this.filterSize);
            oos.writeObject(this.totalVioMatrix);
            oos.writeInt(this.violate_per);
            oos.writeInt(this.save2disk_batch);

            long[] thresholdsToSave = new long[this.numColumns];
            for (int i = 0; i < this.numColumns; i++) {
                thresholdsToSave[i] = this.columnThreshold[i][1];
            }
            oos.writeObject(thresholdsToSave);

            if(isDeletionMode){
                oos.writeObject(this.rowCountOfColumn);
            }
            oos.writeInt(this.microProbingThreshold);

            System.out.println("【Success】Save to: " + contextFilePath);
        } catch (IOException e) {
            System.err.println("Fail to save: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void initializeCommon(List<TableInfo> tables)
            throws InputGenerationException, InputIterationException, AlgorithmConfigurationException {
        this.tempFolder = new File(this.tempFolderPath + File.separator);
        this.diskBack = new DiskBack(this.tempFolder);
        this.filterDiskBack = new FilterDiskBack(this.tempFolder);
        FileUtils.cleanDirectory(this.tempFolder);
        this.tableColumnStartIndexes = new int[this.tableNames.length];
        this.availableMemory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
        this.maxMemoryUsage = (long) ((float) this.availableMemory
                * ((float) this.maxMemoryUsagePercentageUp / 100.0F));
        this.maxMemoryUsageLow = (long) ((float) this.availableMemory
                * ((float) this.maxMemoryUsagePercentageLow / 100.0F));
        this.columnNames = new ArrayList();
        this.maxPartitionHeap = new PriorityQueue<>(10, new Comparator<PartitionSize>() {
            @Override
            public int compare(PartitionSize o1, PartitionSize o2) {
                return o2.getSize() - o1.getSize();
            }
        });
        this.maxPartitionHeapCurr = new PriorityQueue<>(10, new Comparator<PartitionSize>() {
            @Override
            public int compare(PartitionSize o1, PartitionSize o2) {
                return o2.getSize() - o1.getSize();
            }
        });
        this.bucketSizeRange = new ArrayList<>();
        this.partition_backed = new ArrayList<>();

        int table;
        for (table = 0; table < this.tableNames.length; ++table) {
            this.tableColumnStartIndexes[table] = this.columnNames.size();
            this.collectStatisticsFrom(tables.get(table).selectInputGenerator());
        }
        this.numColumns = this.columnNames.size();
        this.bucketInMemPerPart = new Integer[this.numPartitionsPerColumn];
        for (int i = 0; i < this.bucketInMemPerPart.length; i++) {
            this.bucketInMemPerPart[i] = 0;
        }

        this.lineNameToIndex = new HashMap<>();
        for (int i = 0; i < numColumns; i++)
            lineNameToIndex.put(columnNames.get(i), i);
        this.leftViolation = new boolean[numColumns];
        this.rightViolation = new boolean[numColumns];
        this.violation = new boolean[numColumns];
        this.isNotFullMem = new boolean[numColumns];
        this.violationMatrix = new int[numColumns][numColumns];
        for (int i = 0; i < numColumns; i++)
            violationMatrix[i][i] = -1;
        this.totalVioMatrix = new long[numColumns][numColumns];
        this.rowVioNum = new int[numColumns];
        for (int i = 0; i < numColumns; i++)
            rowVioNum[i] = 1;
        this.lineVioNum = new int[numColumns];
        for (int i = 0; i < numColumns; i++)
            lineVioNum[i] = 1;

    }

    private void initializeInsertion(List<TableInfo> tables)
            throws InputGenerationException, InputIterationException, AlgorithmConfigurationException {
        this.partitions_list = new ArrayList<>();
        this.filterInsert_list = new ArrayList<>();
        this.columnThreshold = new int[numColumns][2];
    }

    private void initializeDeletion(List<TableInfo> tables)
            throws InputGenerationException, InputIterationException, AlgorithmConfigurationException {
        this.partitions_delete = new ArrayList<>();
        this.filterDelete_list = new ArrayList<>();

        this.rowCountOfColumn = new int[numColumns];
        this.columnThreshold = new int[numColumns][2];
    }

    private void collectStatisticsFrom(RelationalInputGenerator inputGenerator)
            throws InputIterationException, InputGenerationException, AlgorithmConfigurationException {
        RelationalInput input = null;
        try {
            input = inputGenerator.generateNewCopy();
            Iterator var3 = input.columnNames().iterator();
            while (var3.hasNext()) {
                String columnName = (String) var3.next();
                this.columnNames.add(columnName);
            }
        } finally {
            FileUtils.close(input);
        }
    }

    protected boolean partitioningInsertion(List<TableInfo> tables)
            throws InputIterationException, InputGenerationException, AlgorithmConfigurationException {
        for (int tableIndex = 0; tableIndex < this.tableNames.length; ++tableIndex) {
            int numTableColumns = this.tableColumnStartIndexes.length > tableIndex + 1
                    ? this.tableColumnStartIndexes[tableIndex + 1] - this.tableColumnStartIndexes[tableIndex]
                    : this.numColumns - this.tableColumnStartIndexes[tableIndex];
            TableInfo table = tables.get(tableIndex);
            RelationalInputGenerator generator = table.selectInputGenerator();
            RelationalInput input = generator.generateNewCopy();
            int startTableColumnIndex = this.tableColumnStartIndexes[tableIndex];
            for (int i = 0; i < numPartitionsPerColumn; i++) {
                this.bucketInMemPerPart[i] += numTableColumns;
            }
            // partitions init, filters init
            for (int i = 0; i < numTableColumns; ++i) {
                List<Set<String>> attributePartitions = new ArrayList();
                List<Boolean> attributePartitionBacks = new ArrayList<>();
                List<ThreeLayersFilter> partitionBlooms = new ArrayList<>();
                List<int[]> bucketSizeRanges = new ArrayList<>();
                for (int ptnIndex = 0; ptnIndex < this.numPartitionsPerColumn; ++ptnIndex) {
                    attributePartitions.add(new HashSet());
                    attributePartitionBacks.add(false);
                    partitionBlooms.add(new ThreeLayersFilter(-1));
                    bucketSizeRanges.add(new int[] { 0, 0 });
                }
                this.partitions_list.add(attributePartitions);
                this.partition_backed.add(attributePartitionBacks);
                this.filterInsert_list.add(partitionBlooms);
                this.bucketSizeRange.add(bucketSizeRanges);
            }

            // read data files one by one
            int numValuesSinceLastMemoryCheck = 0;
            List<String> values;
            while (input.hasNext()) {
                values = input.next();
                int pNumber;
                for (int columnNumber = 0; columnNumber < numTableColumns; ++columnNumber) {
                    String value = (String) values.get(columnNumber);
                    if (value == null)
                        continue;
                    pNumber = this.calculatePartitionFor(value);
                    if (((Set) ((List) partitions_list.get(startTableColumnIndex + columnNumber)).get(pNumber))
                            .add(value)) {
                        ++numValuesSinceLastMemoryCheck;
                    }
                    if (numValuesSinceLastMemoryCheck >= this.memoryCheckFrequency) {
                        numValuesSinceLastMemoryCheck = 0;
                        if (ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() > this.maxMemoryUsage) {
                            int count = 0;
                            while (ManagementFactory.getMemoryMXBean().getHeapMemoryUsage()
                                    .getUsed() > this.maxMemoryUsageLow) {
                                findMaxBucketWriteBack(numTableColumns, startTableColumnIndex);
                                count++;
                                if (count % 10 == 0)
                                    System.gc();
                            }

                        }
                        maxPartitionHeapCurr.clear();
                    }
                }
            }
            // add to buckect queue ; update bucket size range
            for (int columnNumber = 0; columnNumber < numTableColumns; ++columnNumber) {
                for (int partNumber = 0; partNumber < numPartitionsPerColumn; ++partNumber) {
                    int size = partitions_list.get(startTableColumnIndex + columnNumber).get(partNumber).size();
                    PartitionSize partitionSize = new PartitionSize(startTableColumnIndex + columnNumber, partNumber,
                            size);
                    maxPartitionHeap.add(partitionSize);
                    updateBucketSizeRange(startTableColumnIndex + columnNumber, partNumber, size);
                }
            }
        }
        return true;
    }

    protected boolean partitioningDeletion(List<TableInfo> tables)
            throws InputIterationException, InputGenerationException, AlgorithmConfigurationException {
        for (int tableIndex = 0; tableIndex < this.tableNames.length; ++tableIndex) {
            int numTableColumns = this.tableColumnStartIndexes.length > tableIndex + 1
                    ? this.tableColumnStartIndexes[tableIndex + 1] - this.tableColumnStartIndexes[tableIndex]
                    : this.numColumns - this.tableColumnStartIndexes[tableIndex];
            TableInfo table = tables.get(tableIndex);
            RelationalInputGenerator generator = table.selectInputGenerator();
            RelationalInput input = generator.generateNewCopy();

            int startTableColumnIndex = this.tableColumnStartIndexes[tableIndex];
            for (int i = 0; i < numPartitionsPerColumn; i++) {
                this.bucketInMemPerPart[i] += numTableColumns;
            }

            for (int i = 0; i < numTableColumns; ++i) {
                List<HashMap<String, Integer>> attributePartitions = new ArrayList();
                List<Boolean> attributePartitionBacks = new ArrayList<>();
                List<ThreeLayersFilterDelete> partitionBlooms = new ArrayList<>();
                List<int[]> bucketSizeRanges = new ArrayList<>();
                for (int ptnIndex = 0; ptnIndex < this.numPartitionsPerColumn; ++ptnIndex) {
                    attributePartitions.add(new HashMap<>());
                    attributePartitionBacks.add(false);
                    partitionBlooms.add(new ThreeLayersFilterDelete(-1));
                    bucketSizeRanges.add(new int[] { 0, 0 });
                }
                this.partitions_delete.add(attributePartitions);
                this.partition_backed.add(attributePartitionBacks);
                this.filterDelete_list.add(partitionBlooms);
            }

            // read data files one by one

            int numValuesSinceLastMemoryCheck = 0;

            while(input.hasNext()) {
                List<String> values = input.next();
                for(int columnNumber = 0; columnNumber < numTableColumns; ++columnNumber) {
                    String value = (String)values.get(columnNumber);
                    if(value==null){
                        continue;
                    }

                    this.rowCountOfColumn[startTableColumnIndex + columnNumber]++;
                    int pNumber = this.calculatePartitionFor(value);

                    HashMap<String, Integer> map = partitions_delete.get(startTableColumnIndex + columnNumber)
                            .get(pNumber);
                    if (map.containsKey(value)) {
                        map.put(value, map.get(value) + 1);
                    } else {
                        map.put(value, 1);
                        ++numValuesSinceLastMemoryCheck;
                    }
                    if (numValuesSinceLastMemoryCheck >= this.memoryCheckFrequency) {
                        numValuesSinceLastMemoryCheck = 0;
                        if (ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() > this.maxMemoryUsage) {
                            int count = 0;
                            while (ManagementFactory.getMemoryMXBean().getHeapMemoryUsage()
                                    .getUsed() > this.maxMemoryUsageLow) {
                                findMaxBucketWriteBackDeletion(numTableColumns, startTableColumnIndex);
                                count++;
                                if (count % 10 == 0)
                                    System.gc();
                            }
                        }
                        maxPartitionHeapCurr.clear();
                    }
                }
            }

            for (int columnNumber = 0; columnNumber < numTableColumns; ++columnNumber) {
                for (int partNumber = 0; partNumber < numPartitionsPerColumn; ++partNumber) {
                    int size = this.partitions_delete.get(startTableColumnIndex + columnNumber).get(partNumber).size();
                    PartitionSize partitionSize = new PartitionSize(startTableColumnIndex + columnNumber, partNumber,
                            size);
                    maxPartitionHeap.add(partitionSize);
                }
            }
        }
        return true;
    }

    public void cleanupOldLayer3() {
        System.out.println("Cleaning old Layer 3...");

        if (columnNames == null || columnNames.isEmpty()) {
            return;
        }

        for (int colIndex = 0; colIndex < numColumns; colIndex++) {
            String rawColName = columnNames.get(colIndex);
            if (rawColName == null) continue;

            String safeColName = buildSafeColumnName(rawColName, colIndex);

            for (int partID = 0; partID < numPartitionsPerColumn; partID++) {
                String path = tempFolderPath + File.separator + safeColName + "_" + partID;
                File file = new File(path);
                if (file.exists()) {
                    file.delete();
                }

                File layersFile = new File(path + "_layers.dat");
                if (layersFile.exists()) {
                    layersFile.delete();
                }
            }
        }
    }

    public void rebuildLayer3(List<TableInfo> tables) throws Exception {
        final int COLUMNS_PER_BATCH = save2disk_batch;
        System.out.println("Save the filters, COLUMNS_PER_BATCH: " + COLUMNS_PER_BATCH);

        for (int tableIndex = 0; tableIndex < tableNames.length; tableIndex++) {
            TableInfo table = tables.get(tableIndex);
            int startColIdx = this.tableColumnStartIndexes[tableIndex];
            int numTableCols = (tableIndex + 1 < tableColumnStartIndexes.length)
                    ? tableColumnStartIndexes[tableIndex+1] - startColIdx
                    : numColumns - startColIdx;

            for (int b = 0; b < numTableCols; b += COLUMNS_PER_BATCH) {
                int currentBatchEnd = Math.min(b + COLUMNS_PER_BATCH, numTableCols);
                int currentBatchSize = currentBatchEnd - b;

                Map<String, Integer>[][] batchMaps = new HashMap[currentBatchSize][numPartitionsPerColumn];
                for (int i = 0; i < currentBatchSize; i++) {
                    for (int p = 0; p < numPartitionsPerColumn; p++) {
                        batchMaps[i][p] = new HashMap<>();
                    }
                }

                try (RelationalInput input = table.selectInputGenerator().generateNewCopy()) {
                    while (input.hasNext()) {
                        List<String> row = input.next();
                        for (int i = 0; i < currentBatchSize; i++) {
                            int colIdxInTable = b + i;
                            if (colIdxInTable < row.size()) {
                                String val = row.get(colIdxInTable);
                                if (val != null && !val.isEmpty()) {
                                    int pID = Math.abs(val.hashCode() % numPartitionsPerColumn);
                                    batchMaps[i][pID].put(val, batchMaps[i][pID].getOrDefault(val, 0) + 1);
                                }
                            }
                        }
                    }
                }

                for (int i = 0; i < currentBatchSize; i++) {
                    int globalColIdx = startColIdx + b + i;

                    String rawColName = columnNames.get(globalColIdx);
                    String safeColName = buildSafeColumnName(rawColName, globalColIdx);

                    for (int pID = 0; pID < numPartitionsPerColumn; pID++) {
                        Map<String, Integer> bucketData = batchMaps[i][pID];

                        BitSet bloomSet = new BitSet(filterSize);
                        int[] bloomNum = new int[filterSize];
                        Map<Integer, Map<String, Integer>> groupedByHash = new HashMap<>();

                        if (!bucketData.isEmpty()) {
                            for (Map.Entry<String, Integer> entry : bucketData.entrySet()) {
                                String val = entry.getKey();
                                int freq = entry.getValue();
                                int h = val.hashCode();
                                int idx = ((h ^ (h >>> 16)) & (filterSize - 1));

                                bloomSet.set(idx);
                                bloomNum[idx]++;

                                groupedByHash.computeIfAbsent(idx, k -> new HashMap<>()).put(val, freq);
                            }
                        }

                        String layersFilePath = tempFolderPath + File.separator + safeColName + "_" + pID + "_layers.dat";
                        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(layersFilePath))) {
                            byte[] bitSetBytes = bloomSet.toByteArray();
                            dos.writeInt(bitSetBytes.length);
                            dos.write(bitSetBytes);

                            dos.writeInt(bloomNum.length);
                            for (int count : bloomNum) {
                                dos.writeInt(count);
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }

                        String layer3FilePath = tempFolderPath + File.separator + safeColName + "_" + pID;
                        try (DataOutputStream dos3 = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(layer3FilePath)))) {
                            for (Map.Entry<Integer, Map<String, Integer>> entry : groupedByHash.entrySet()) {
                                int hashIdx = entry.getKey();
                                Map<String, Integer> valMap = entry.getValue();

                                dos3.writeInt(hashIdx);
                                dos3.writeInt(valMap.size());

                                for (Map.Entry<String, Integer> vEntry : valMap.entrySet()) {
                                    byte[] strBytes = vEntry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);

                                    dos3.writeShort(strBytes.length);
                                    dos3.write(strBytes);
                                    dos3.writeInt(vEntry.getValue());
                                }
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    batchMaps[i] = null;
                }
                System.gc();
            }
        }
    }

    public void rebuildLayer3Deletion(List<TableInfo> tables) throws Exception {
        final int COLUMNS_PER_BATCH = save2disk_batch;
        System.out.println("Save the filters, COLUMNS_PER_BATCH: " + COLUMNS_PER_BATCH);

        for (int tableIndex = 0; tableIndex < tableNames.length; tableIndex++) {
            TableInfo table = tables.get(tableIndex);
            int startColIdx = this.tableColumnStartIndexes[tableIndex];
            int numTableCols = (tableIndex + 1 < tableColumnStartIndexes.length)
                    ? tableColumnStartIndexes[tableIndex+1] - startColIdx
                    : numColumns - startColIdx;

            for (int b = 0; b < numTableCols; b += COLUMNS_PER_BATCH) {
                int currentBatchEnd = Math.min(b + COLUMNS_PER_BATCH, numTableCols);
                int currentBatchSize = currentBatchEnd - b;

                Map<String, Integer>[][] batchMaps = new HashMap[currentBatchSize][numPartitionsPerColumn];
                for (int i = 0; i < currentBatchSize; i++) {
                    for (int p = 0; p < numPartitionsPerColumn; p++) {
                        batchMaps[i][p] = new HashMap<>();
                    }
                }

                try (RelationalInput input = table.selectInputGenerator().generateNewCopy()) {
                    while (input.hasNext()) {
                        List<String> row = input.next();
                        for (int i = 0; i < currentBatchSize; i++) {
                            int colIdxInTable = b + i;
                            if (colIdxInTable < row.size()) {
                                String val = row.get(colIdxInTable);
                                if (val != null && !val.isEmpty()) {
                                    int pID = Math.abs(val.hashCode() % numPartitionsPerColumn);
                                    batchMaps[i][pID].put(val, batchMaps[i][pID].getOrDefault(val, 0) + 1);
                                }
                            }
                        }
                    }
                }

                for (int i = 0; i < currentBatchSize; i++) {
                    int globalColIdx = startColIdx + b + i;
                    String rawColName = columnNames.get(globalColIdx);
                    String safeColName = buildSafeColumnName(rawColName, globalColIdx);

                    for (int pID = 0; pID < numPartitionsPerColumn; pID++) {
                        Map<String, Integer> bucketData = batchMaps[i][pID];

                        BitSet bloomSet = new BitSet(filterSize);
                        int[] bloomNum = new int[filterSize];
                        int[] bloomMinFreq = new int[filterSize];
                        Arrays.fill(bloomMinFreq, Integer.MAX_VALUE);

                        Map<Integer, Map<String, Integer>> groupedByHash = new HashMap<>();

                        if (!bucketData.isEmpty()) {
                            for (Map.Entry<String, Integer> entry : bucketData.entrySet()) {
                                String val = entry.getKey();
                                int freq = entry.getValue();
                                int h = val.hashCode();
                                int idx = ((h ^ (h >>> 16)) & (filterSize - 1));

                                bloomSet.set(idx);
                                bloomNum[idx]++;

                                bloomMinFreq[idx] = Math.min(bloomMinFreq[idx], freq);

                                groupedByHash.computeIfAbsent(idx, k -> new HashMap<>()).put(val, freq);
                            }
                        }

                        for (int k = 0; k < filterSize; k++) {
                            if (bloomMinFreq[k] == Integer.MAX_VALUE) {
                                bloomMinFreq[k] = 0;
                            }
                        }

                        String layersFilePath = tempFolderPath + File.separator + safeColName + "_" + pID + "_layers.dat";
                        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(layersFilePath))) {
                            byte[] bitSetBytes = bloomSet.toByteArray();
                            dos.writeInt(bitSetBytes.length);
                            dos.write(bitSetBytes);

                            dos.writeInt(bloomNum.length);
                            for (int count : bloomNum) {
                                dos.writeInt(count);
                            }

                            dos.writeInt(bloomMinFreq.length);
                            for (int minFreq : bloomMinFreq) {
                                dos.writeInt(minFreq);
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }

                        String layer3FilePath = tempFolderPath + File.separator + safeColName + "_" + pID;
                        try (DataOutputStream dos3 = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(layer3FilePath)))) {
                            for (Map.Entry<Integer, Map<String, Integer>> entry : groupedByHash.entrySet()) {
                                int hashIdx = entry.getKey();
                                Map<String, Integer> valMap = entry.getValue();

                                dos3.writeInt(hashIdx);
                                dos3.writeInt(valMap.size());

                                for (Map.Entry<String, Integer> vEntry : valMap.entrySet()) {
                                    byte[] strBytes = vEntry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                    dos3.writeShort(strBytes.length);
                                    dos3.write(strBytes);
                                    dos3.writeInt(vEntry.getValue());
                                }
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    batchMaps[i] = null;
                }
                System.gc();
            }
        }
    }

    private String buildSafeColumnName(String rawName, int colIndex) {
        if (rawName == null) {
            return "col" + colIndex;
        }
        String cleaned = rawName
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_")
                .replaceAll("[;,-]+", "_")
                .replaceAll("_+", "_");

        int MAX_LEN = 40;
        if (cleaned.length() > MAX_LEN) {
            cleaned = cleaned.substring(0, MAX_LEN);
        }
        return "col" + colIndex + "_" + cleaned;
    }

    protected void findMaxBucketWriteBack(int numTableColumns, int startTableColumnIndex) {
        System.out.println("find max bucket and write back ");
        if (maxPartitionHeap.isEmpty()) {
            if (maxPartitionHeapCurr.isEmpty()) {
                for (int k = 0; k < numTableColumns; ++k) {
                    for (int partNumber = 0; partNumber < numPartitionsPerColumn; ++partNumber) {
                        int size = partitions_list.get(startTableColumnIndex + k).get(partNumber).size();
                        PartitionSize partitionSize = new PartitionSize(startTableColumnIndex + k, partNumber, size);
                        maxPartitionHeapCurr.add(partitionSize);
                    }
                }
            } else {
                PartitionSize p = maxPartitionHeapCurr.poll();
                if (p != null) {
                    int colIndex = p.getAttributeID();
                    int partIndex = p.getPartitionID();
                    String rawColName = columnNames.get(colIndex);
                    HashSet set = (HashSet) ((List) partitions_list.get(colIndex)).get(partIndex);
                    updateBucketSizeRange(colIndex, partIndex, set.size());
                    String safeColName = buildSafeColumnName(rawColName, colIndex);

                    diskBack.writePartition(safeColName, partIndex, set);

                    this.bucketInMemPerPart[partIndex] -= 1;
                    partitions_list.get(colIndex).set(partIndex, new HashSet<>());
                    this.partition_backed.get(colIndex).set(partIndex, true);
                    this.isNotFullMem[colIndex] = true;
                }
            }
        } else {
            PartitionSize p = maxPartitionHeap.poll();
            if (p != null) {
                int colIndex = p.getAttributeID();
                int partIndex = p.getPartitionID();
                String rawColName = columnNames.get(colIndex);
                String safeColName = buildSafeColumnName(rawColName, colIndex);
                System.out.println("write bucket to disk, column index  " + colIndex + " partition index :" + partIndex
                        + "column name :" + safeColName);

                diskBack.writePartition(safeColName, partIndex, (HashSet) ((List) partitions_list.get(colIndex)).get(partIndex));

                this.bucketInMemPerPart[partIndex] -= 1;
                partitions_list.get(colIndex).set(partIndex, new HashSet<>());
                this.partition_backed.get(colIndex).set(partIndex, true);
                this.isNotFullMem[colIndex] = true;
            }
        }
    }

    protected void findMaxBucketWriteBackDeletion(int numTableColumns, int startTableColumnIndex) {
        System.out.println("find max bucket and write back ");
        if (maxPartitionHeap.isEmpty()) {
            if (maxPartitionHeapCurr.isEmpty()) {
                for (int k = 0; k < numTableColumns; ++k) {
                    for (int partNumber = 0; partNumber < numPartitionsPerColumn; ++partNumber) {
                        int size = this.partitions_delete.get(startTableColumnIndex + k).get(partNumber).size();
                        PartitionSize partitionSize = new PartitionSize(startTableColumnIndex + k, partNumber, size);
                        maxPartitionHeapCurr.add(partitionSize);
                    }
                }
            } else {
                PartitionSize p = maxPartitionHeapCurr.poll();
                if (p != null) {
                    int colIndex = p.getAttributeID();
                    int partIndex = p.getPartitionID();
                    String colName = columnNames.get(colIndex);
                    HashMap<String, Integer> map = partitions_delete.get(colIndex).get(partIndex);
                    diskBack.writePartition(colName, partIndex, map);
                    this.bucketInMemPerPart[partIndex] -= 1;
                    ((List) partitions_delete.get(colIndex)).set(partIndex, new HashMap<>());
                    this.partition_backed.get(colIndex).set(partIndex, true);
                    this.isNotFullMem[colIndex] = true;
                    System.gc();
                }
            }
        } else {
            PartitionSize p = maxPartitionHeap.poll();
            if (p != null) {
                int colIndex = p.getAttributeID();
                int partIndex = p.getPartitionID();
                String colName = columnNames.get(colIndex);
                System.out.println("write bucket to disk, column index  " + colIndex + " partition index :" + partIndex
                        + "column name :" + colName);
                diskBack.writePartition(colName, partIndex, partitions_delete.get(colIndex).get(partIndex));
                this.bucketInMemPerPart[partIndex] -= 1;
                partitions_delete.get(colIndex).set(partIndex, new HashMap<>());
                this.partition_backed.get(colIndex).set(partIndex, true);
                this.isNotFullMem[colIndex] = true;
                System.gc();
            }
        }
    }

    private void updateBucketSizeRange(int colIndex, int partIndex, int size) {
        int sizeLowBound = bucketSizeRange.get(colIndex).get(partIndex)[0];
        bucketSizeRange.get(colIndex).get(partIndex)[0] = Math.max(sizeLowBound, size);
        bucketSizeRange.get(colIndex).get(partIndex)[1] += size;
    }

    public void buildFilter(int filterSize, String colName, int colIndex, int partIndex, Set<String> values) {
        ThreeLayersFilter<String> threeLayersFilter = new ThreeLayersFilter<>(filterSize,colName,colIndex,partIndex);
        for (String s : values) {
            if (s == null) continue;
            threeLayersFilter.addBloomSet(s);
        }
        filterInsert_list.get(colIndex).set(partIndex, threeLayersFilter);
    }

    public void buildFilterDeletion(int filterSize, String colName, int colIndex, int partIndex,
                                    HashMap<String, Integer> map) {
        ThreeLayersFilterDelete<String> threeLayersFilterDelete = new ThreeLayersFilterDelete<>(filterSize, colName,
                colIndex, partIndex);
        for (String s : map.keySet()) {
            threeLayersFilterDelete.addBloomSet(s, map.get(s));
        }
        filterDelete_list.get(colIndex).set(partIndex, threeLayersFilterDelete);
    }

    protected List<InclusionDependency> AINDDiscovery() throws SQLException, ClassNotFoundException {
        maxPartitionHeapCurr.clear();
        computeVioLowUpBound();
        Integer[] partitionIdxsSortedBySize = getPartitionIdxsSortedBySize(this.bucketInMemPerPart);
        List<InclusionDependency> results = new ArrayList<>();
        int partitionCount = 0;
        for (int partitionNum : partitionIdxsSortedBySize) {
            for (int colNum = 0; colNum < numColumns; colNum++) {
                if (filterInsert_list.get(colNum).get(partitionNum).getColIndex() != -1)
                    continue;
                Set<String> bucketInMem = partitions_list.get(colNum).get(partitionNum);
                Set<String> bucketOutMem = null;
                if (partition_backed.get(colNum).get(partitionNum)) {
                    String safeColName = buildSafeColumnName(columnNames.get(colNum), colNum);

                    bucketOutMem = diskBack.readPartition(safeColName, partitionNum);

                    bucketInMem.addAll(bucketOutMem);
                }
                int bucketSize = bucketInMem.size();
                updateVioLowUpBound(colNum, partitionNum, bucketSize);
            }
            for (int colNum = 0; colNum < numColumns; colNum++) {
                Set<String> bucketInMem = partitions_list.get(colNum).get(partitionNum);
                buildFilter(this.filterSize, this.columnNames.get(colNum), colNum, partitionNum, bucketInMem);

            }
            for (int colNum = 0; colNum < numColumns; colNum++) {
                partitions_list.get(colNum).set(partitionNum, new HashSet<>());
            }

            for (int i = 0; i < numColumns; i++) {
                if (leftViolation[i]) continue;
                ThreeLayersFilter l = filterInsert_list.get(i).get(partitionNum);
                for (int j = 0; j < numColumns; j++) {
                    if (j == i) continue;
                    if (violationMatrix[i][j] == -1) continue;
                    ThreeLayersFilter r = filterInsert_list.get(j).get(partitionNum);
                    List<List<Integer>> left = l.getValues();
                    List<List<Integer>> right = r.getValues();

                    int count = 0;
                    BitSet a = l.getBloomSet();
                    for (int k = a.nextSetBit(0); k >= 0; k = a.nextSetBit(k + 1)) {
                        if (k == Integer.MAX_VALUE) {
                            break;
                        }
                        if ((l.getBloomNum()[k] - r.getBloomNum()[k]) > 0) {
                            count += l.getBloomNum()[k] - r.getBloomNum()[k];
                            if (count > columnThreshold[i][1])
                                break;
                        }
                    }
                    if (count > columnThreshold[i][1]) {
                        this.totalVioMatrix[i][j] += count;
                        violationMatrix[i][j] = -1;
                        addLeftOrRightVio(i, j);
                        continue;
                    }
                    // exact calculation
                    if (violationMatrix[i][j] != -1) {
                        updateMatrixFromFilterInsertion(i, j, partitionNum, left, right);
                    }
                }
            }
            for (int colNum = 0; colNum < numColumns; colNum++) {
                // filterInsert_list.get(colNum).set(partitionNum, null);
            }
            partitionCount++;
            if (partitionCount > 10
                    && ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() > this.maxMemoryUsage) {
                partitionCount = 0;
            }
        }
        matrec = new int[numColumns][numColumns];

        for (int i = 0; i < numColumns; i++) {
            for (int j = 0; j < numColumns; j++) {
                if (violationMatrix[i][j] == -1 || violationMatrix[i][j] > columnThreshold[i][1])
                    continue;
                ColumnIdentifier l = new ColumnIdentifier(tableNames[findTableIndex(i)], columnNames.get(i));
                ColumnPermutation lhs = new ColumnPermutation(l);
                ColumnIdentifier r = new ColumnIdentifier(tableNames[findTableIndex(j)], columnNames.get(j));
                ColumnPermutation rhs = new ColumnPermutation(r);
                InclusionDependency ind = new InclusionDependency(lhs, rhs);
                results.add(ind);
                matrec[i][j] = 1;
            }
        }
        System.out.println("The number of AINDs: " + results.size());
        for (InclusionDependency ind : results) {
            System.out.println(ind);
        }
        return results;
    }

    protected List<InclusionDependency> AINDDiscoveryDeletion() throws SQLException, ClassNotFoundException {
        int tableIndex = this.tableNames.length - 1;
        int numTableColumns = this.numColumns - this.tableColumnStartIndexes[tableIndex];
        int startTableColumnIndex = this.tableColumnStartIndexes[tableIndex];
        while (ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() > this.maxMemoryUsage) {
            findMaxBucketWriteBack(numTableColumns, startTableColumnIndex);
        }
        maxPartitionHeapCurr.clear();

        computeVioBound();
        Integer[] partitionIdxsSortedBySize = getPartitionIdxsSortedBySize(this.bucketInMemPerPart);
        List<InclusionDependency> results = new ArrayList<>();
        int partitionCount = 0;
        for (int partitionNum : partitionIdxsSortedBySize) {
            for (int colNum = 0; colNum < numColumns; colNum++) {
                if (filterDelete_list.get(colNum).get(partitionNum).getColIndex() != -1)
                    continue;
                HashMap<String, Integer> bucketInMem = partitions_delete.get(colNum).get(partitionNum);
                HashMap<String, Integer> bucketOutMem = null;
                if (partition_backed.get(colNum).get(partitionNum)) {
                    String safeColName = buildSafeColumnName(columnNames.get(colNum), colNum);
                    bucketOutMem = diskBack.readPartitionMap(safeColName, partitionNum);
//					bucketOutMem = diskBack.readPartitionMap(columnNames.get(colNum), partitionNum);
                    for (String s : bucketOutMem.keySet()) {
                        if (bucketInMem.containsKey(s)) {
                            bucketInMem.put(s, bucketInMem.get(s) + bucketOutMem.get(s));
                        } else {
                            bucketInMem.put(s, bucketOutMem.get(s));
                        }
                    }
                }
            }
            for (int colNum = 0; colNum < numColumns; colNum++) {
                HashMap<String, Integer> bucketInMem = partitions_delete.get(colNum).get(partitionNum);
                buildFilterDeletion(this.filterSize, this.columnNames.get(colNum), colNum, partitionNum, bucketInMem);
            }

            // free bucket
            for (int colNum = 0; colNum < numColumns; colNum++) {
                partitions_delete.get(colNum).set(partitionNum, new HashMap<>());
            }

            for (int i = 0; i < numColumns; i++) {
                if (leftViolation[i])
                    continue;
                ThreeLayersFilterDelete l = filterDelete_list.get(i).get(partitionNum);
                for (int j = 0; j < numColumns; j++) {
                    if (j == i)
                        continue;
                    if (violationMatrix[i][j] == -1)
                        continue;
                    ThreeLayersFilterDelete r = filterDelete_list.get(j).get(partitionNum);
                    List<HashMap<String, Integer>> left = l.getValues();
                    List<HashMap<String, Integer>> right = r.getValues();
                    long count = 0;
                    BitSet a = l.getBloomSet();
                    for (int k = a.nextSetBit(0); k >= 0; k = a.nextSetBit(k + 1)) {
                        if (k == Integer.MAX_VALUE) {
                            break;
                        }
                        if ((l.getBloomNum()[k] - r.getBloomNum()[k]) > 0) {
                            count += (long)(l.getBloomNum()[k] - r.getBloomNum()[k]) * l.getBloomMinCount()[k];

                            if (this.violationMatrix[i][j] + count > columnThreshold[i][1])
                                break;
                        }
                    }
                    if (this.violationMatrix[i][j] + count > columnThreshold[i][1]) {

                        this.totalVioMatrix[i][j] += count;

                        violationMatrix[i][j] = -1;
                        addLeftOrRightVio(i, j);
                        continue;
                    }
                    if (violationMatrix[i][j] != -1) {
                        updateMatrixFromFilterDeletion(i, j, partitionNum, left, right);
                    }
                }
            }
            for (int colNum = 0; colNum < numColumns; colNum++) {
                filterDelete_list.get(colNum).set(partitionNum, null);
            }
            partitionCount++;
            if (partitionCount > 10
                    && ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() > this.maxMemoryUsage) {
                System.gc();
                partitionCount = 0;
            }
        }

        matrec = new int[numColumns][numColumns];
        for (int i = 0; i < numColumns; i++) {
            for (int j = 0; j < numColumns; j++) {
                if (violationMatrix[i][j] == -1 || violationMatrix[i][j] > columnThreshold[i][1])
                    continue;
                ColumnIdentifier l = new ColumnIdentifier(tableNames[findTableIndex(i)], columnNames.get(i));
                ColumnPermutation lhs = new ColumnPermutation(l);
                ColumnIdentifier r = new ColumnIdentifier(tableNames[findTableIndex(j)], columnNames.get(j));
                ColumnPermutation rhs = new ColumnPermutation(r);
                InclusionDependency ind = new InclusionDependency(lhs, rhs);
                results.add(ind);

                matrec[i][j] = 1;
            }
        }
        return results;
    }

    private void computeVioBound(){
        for (int i = 0; i < numColumns; i++) {
            this.columnThreshold[i][1] = (int)(this.rowCountOfColumn[i] * (violate_per / 10000.0F));
        }
    }

    private int findTableIndex(int colNum) {
        // for a given column index, return table index that it belongs to
        for (int i = 0; i < tableColumnStartIndexes.length - 1; i++) {
            if (colNum >= tableColumnStartIndexes[i] && colNum < tableColumnStartIndexes[i + 1])
                return i;
        }
        return tableColumnStartIndexes.length - 1;
    }

    private void computeVioLowUpBound() {
        // compute allowed violation number of each column
        for (int colIndex = 0; colIndex < numColumns; colIndex++) {
            int low = 0, up = 0;
            for (int partIndex = 0; partIndex < this.numPartitionsPerColumn; partIndex++) {
                low += bucketSizeRange.get(colIndex).get(partIndex)[0];
                up += bucketSizeRange.get(colIndex).get(partIndex)[1];
            }
            columnThreshold[colIndex][0] = (int) (low * (violate_per / 10000.0F));
            columnThreshold[colIndex][1] = (int) (up * (violate_per / 10000.0F));
        }
        return;
    }

    private void updateVioLowUpBound(int colNum, int partNum, int bucketSize) {
        // update range by new bucket size
        if (bucketSizeRange.get(colNum).get(partNum)[0] == bucketSizeRange.get(colNum).get(partNum)[1]
                || columnThreshold[colNum][0] == columnThreshold[colNum][1]) {
            return;
        }
        bucketSizeRange.get(colNum).get(partNum)[0] = bucketSize;
        bucketSizeRange.get(colNum).get(partNum)[1] = bucketSize;
        int low = 0, up = 0;
        for (int partIndex = 0; partIndex < this.numPartitionsPerColumn; partIndex++) {
            low += bucketSizeRange.get(colNum).get(partIndex)[0];
            up += bucketSizeRange.get(colNum).get(partIndex)[1];
        }
        columnThreshold[colNum][0] = (int) (low * (violate_per / 10000.0F));
        columnThreshold[colNum][1] = (int) (up * (violate_per / 10000.0F));
        return;
    }

    private void updateMatrixFromFilterInsertion(int leftCol, int rightCol, int partID, List<List<Integer>> left, List<List<Integer>> right){
        int count = 0;
        for (int index = 0; index < left.size(); index++) {
            if (right.get(index).containsAll(left.get(index)))
                continue;
            else {
                if (this.violationMatrix[leftCol][rightCol] + 1 > columnThreshold[leftCol][1]) {
                    this.totalVioMatrix[leftCol][rightCol] += 1;
                    violationMatrix[leftCol][rightCol] = -1;
                    addLeftOrRightVio(leftCol, rightCol);
                    break;
                }
            }

            List<Integer> tempList=new ArrayList<>(left.get(index));
            tempList.retainAll(right.get(index));
            int violationsInThisBucket = left.get(index).size() - tempList.size();

            count += violationsInThisBucket;
            this.violationMatrix[leftCol][rightCol] += violationsInThisBucket;

            this.totalVioMatrix[leftCol][rightCol] += violationsInThisBucket;
            if (this.violationMatrix[leftCol][rightCol] > columnThreshold[leftCol][1]) {
                violationMatrix[leftCol][rightCol] = -1;
                addLeftOrRightVio(leftCol, rightCol);
                break;
            }
        }
    }

    private void updateMatrixFromFilterDeletion(int leftCol, int rightCol, int partID,
                                                List<HashMap<String, Integer>> left, List<HashMap<String, Integer>> right) {
        for (int index = 0; index < left.size(); index++) {
            HashMap<String, Integer> tempList = left.get(index);
            for (String s : tempList.keySet()) {
                if (!right.get(index).keySet().contains(s)) {
                    int vioCount = left.get(index).get(s);
                    this.violationMatrix[leftCol][rightCol] += vioCount;
                    this.totalVioMatrix[leftCol][rightCol] += vioCount;
                }
            }
            if (this.violationMatrix[leftCol][rightCol] > columnThreshold[leftCol][1]) {
                violationMatrix[leftCol][rightCol] = -1;
                addLeftOrRightVio(leftCol, rightCol);
                break;
            }
        }
    }

    private void addLeftOrRightVio(int i, int j) {
        rowVioNum[i]++;
        if (rowVioNum[i] >= numColumns) {
            leftViolation[i] = true;
            if (rightViolation[i])
                violation[i] = true;
        }
        lineVioNum[j]++;
        if (lineVioNum[j] >= numColumns) {
            rightViolation[j] = true;
            if (leftViolation[j])
                violation[j] = true;
        }
    }

    private Integer[] getPartitionIdxsSortedBySize(Integer[] arr) {
        Integer[] indexes = new Integer[arr.length];
        for (int i = 0; i < arr.length; i++) {
            indexes[i] = i;
        }
        Arrays.sort(indexes, new Comparator<Integer>() {
            @Override
            public int compare(Integer o1, Integer o2) {
                return arr[o2].compareTo(arr[o1]);
            }
        });
        return indexes;
    }

    private int calculatePartitionFor(String value) {
        return Math.abs(value.hashCode() % this.numPartitionsPerColumn);
    }

    protected void emit(List<InclusionDependency> results)
            throws CouldNotReceiveResultException, ColumnNameMismatchException {
        for (InclusionDependency ind : results)
            this.resultReceiver.receiveResult(ind);
    }

    @Override
    public String toString() {
        return this.getClass().getName();
    }

    protected String getAuthorName() {
        return "CYT";
    }

    protected String getDescriptionText() {
        return "IncAIND discovery";
    }
}
