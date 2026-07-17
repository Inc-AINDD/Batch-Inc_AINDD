package de.metanome.algorithms.aindd;

import java.io.*;
import java.util.*;
import de.metanome.algorithm_integration.AlgorithmConfigurationException;
import de.metanome.algorithm_integration.AlgorithmExecutionException;
import de.metanome.algorithm_integration.ColumnIdentifier;
import de.metanome.algorithm_integration.ColumnPermutation;
import de.metanome.algorithm_integration.results.InclusionDependency;
import de.metanome.algorithms.aindd.config.Configuration;
import de.uni_potsdam.utils.FileUtils;
import de.metanome.algorithm_integration.input.InputGenerationException;
import de.metanome.algorithm_integration.input.InputIterationException;
import de.metanome.algorithm_integration.input.RelationalInput;
import de.metanome.algorithm_integration.input.RelationalInputGenerator;
import java.io.*;
import java.util.*;
import de.metanome.algorithm_integration.input.*;
import de.metanome.algorithm_integration.result_receiver.InclusionDependencyResultReceiver;
import de.metanome.algorithms.aindd.structures.*;


public class IncrementalStatsAlgorithm {

    protected Configuration configuration;
    protected List<String> columnNames;
    protected int numColumns;
    protected int numPartitionsPerColumn;
    protected int filtersize;
    protected int violate_per;
    protected int batchSize;
    protected int microProbingThreshold;
    public InclusionDependencyResultReceiver resultReceiver = null;
    protected List<List<SplitThreeLayersFilter>> globalFilters = null;
    protected String tempFolderPath = "AINDD_temp2";
    protected boolean dataDeletion = false;
    protected boolean dataInsertion = false;
    protected int[][] oldMatrix;
    protected String[] tableNames = null;
    protected List<Integer> insertStatusList = new ArrayList<>();
    protected List<Integer> deleteStatusList = new ArrayList<>();
    protected List<Set<String>> insertedValuesList = new ArrayList<>();
    protected List<Set<String>> deletedValuesList = new ArrayList<>();
    protected long[] thresholds;
    protected long[][] totalVioMatrix;

    public void execute(Configuration configuration) throws AlgorithmExecutionException {
        this.configuration = configuration;
        this.initialize(configuration.getRelationalInputGenerators().get(0));

        if(dataDeletion && dataInsertion) this.processBatchUpdates();
        else if (dataDeletion) this.processDeletionData();
        else this.processInsertionData();

        this.updateMatrix(oldMatrix, totalVioMatrix, insertStatusList, deleteStatusList);

        if (thresholds != null && thresholds.length == numColumns) {
            validateDirtyCandidates(oldMatrix);
        }

        List<InclusionDependency> results = new ArrayList<>();

        for (int i = 0; i < numColumns; i++) {
            for (int j = 0; j < numColumns; j++) {
                if (oldMatrix[i][j] == 1 && i != j) {
                    ColumnIdentifier leftCol = new ColumnIdentifier(this.tableNames[0], this.columnNames.get(i));
                    ColumnIdentifier rightCol = new ColumnIdentifier(this.tableNames[0], this.columnNames.get(j));

                    InclusionDependency ind = new InclusionDependency(
                            new ColumnPermutation(leftCol),
                            new ColumnPermutation(rightCol)
                    );
                    results.add(ind);

                    if (this.resultReceiver != null) {
                        try {
                            this.resultReceiver.receiveResult(ind);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }

        System.out.println("The number of AINDs: " + results.size());

        System.out.println("Updating context...");
        try {
            String contextFilePath = this.tempFolderPath + File.separator + "aindd_context.ser";
            this.saveContextToDisk(contextFilePath);

            for (int i = 0; i < numColumns; i++) {
                for (int p = 0; p < numPartitionsPerColumn; p++) {
                    this.globalFilters.get(i).get(p).saveLayersToDisk();
                }
            }
            System.out.println("Successfully updated context!");
        } catch (Exception e) {
            throw new AlgorithmExecutionException("Fail to update!", e);
        }
    }

    public void loadContextFromDisk(String contextFilePath) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(contextFilePath))) {
            this.columnNames = (List<String>) ois.readObject();
            this.numColumns = ois.readInt();
            this.oldMatrix = (int[][]) ois.readObject();
            this.tableNames = (String[]) ois.readObject();
            this.numPartitionsPerColumn = ois.readInt();
            this.filtersize = ois.readInt();
            this.totalVioMatrix = (long[][]) ois.readObject();
            this.violate_per = ois.readInt();
            this.batchSize = ois.readInt();
            this.thresholds = (long[]) ois.readObject();
            this.microProbingThreshold = ois.readInt();
        }
    }

    public void saveContextToDisk(String contextFilePath) throws Exception {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(contextFilePath))) {
            oos.writeObject(this.columnNames);
            oos.writeInt(this.numColumns);
            oos.writeObject(this.oldMatrix);
            oos.writeObject(this.tableNames);
            oos.writeInt(this.numPartitionsPerColumn);
            oos.writeInt(this.filtersize);
            oos.writeObject(this.totalVioMatrix);
            oos.writeInt(this.violate_per);
            oos.writeInt(this.batchSize);
            oos.writeObject(this.thresholds);
            oos.writeInt(this.microProbingThreshold);
        }
    }

    private String buildSafeColumnName(String rawName, int colIndex) {
        if (rawName == null) return "col" + colIndex;
        String cleaned = rawName
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_")
                .replaceAll("[;,-]+", "_")
                .replaceAll("_+", "_");
        return "col" + colIndex + "_" + cleaned;
    }

    private void initialize(RelationalInputGenerator inputGenerator)
            throws InputGenerationException, AlgorithmConfigurationException, InputIterationException {

        if (this.columnNames == null || this.columnNames.isEmpty()) {
            this.columnNames = new ArrayList<>();
            RelationalInput input = inputGenerator.generateNewCopy();
            try {
                for (String name : input.columnNames()) {
                    this.columnNames.add(name);
                }
            } finally {
                FileUtils.close(input);
            }
            this.numColumns = columnNames.size();
        }

        this.globalFilters = new ArrayList<>();
        for (int i = 0; i < numColumns; i++) {
            List<SplitThreeLayersFilter> partitions = new ArrayList<>();
            for (int p = 0; p < numPartitionsPerColumn; p++) {
                String safeColName = buildSafeColumnName(columnNames.get(i), i);
                String layer3Path = this.tempFolderPath + File.separator + safeColName + "_" + p;

                partitions.add(new SplitThreeLayersFilter(filtersize, layer3Path));
            }
            globalFilters.add(partitions);
        }
    }

    private void processBatchUpdates() throws AlgorithmExecutionException {
        deleteStatusList.clear();
        insertStatusList.clear();
        insertedValuesList.clear();
        deletedValuesList.clear();

        int maxAllowedBuckets = (int) (numPartitionsPerColumn * filtersize * (this.microProbingThreshold / 10000.0D));

        for (int b = 0; b < numColumns; b += batchSize) {
            int endCol = Math.min(b + batchSize, numColumns);
            int currentBatchSize = endCol - b;

            List<List<Map<String, int[]>>> pendingDeletions = new ArrayList<>(currentBatchSize);
            List<List<Map<String, int[]>>> pendingInsertions = new ArrayList<>(currentBatchSize);

            for (int i = 0; i < currentBatchSize; i++) {
                List<Map<String, int[]>> byPartitionDel = new ArrayList<>(numPartitionsPerColumn);
                List<Map<String, int[]>> byPartitionIns = new ArrayList<>(numPartitionsPerColumn);
                for (int p = 0; p < numPartitionsPerColumn; p++) {
                    byPartitionDel.add(new HashMap<>(16384));
                    byPartitionIns.add(new HashMap<>(16384));
                }
                pendingDeletions.add(byPartitionDel);
                pendingInsertions.add(byPartitionIns);
            }

            loadDataIntoContainers(1, pendingDeletions, b, endCol);
            loadDataIntoContainers(2, pendingInsertions, b, endCol);

            for (int col = b; col < endCol; col++) {
                int batchColIdx = col - b;
                int totalDeletedDistinctCount = 0;
                int totalInsertedDistinctCount = 0;

                Set<String> tempInsertedValues = new HashSet<>();
                Set<String> tempDeletedValues = new HashSet<>();
                int touchedBuckets = 0;
                boolean isAborted = false;

                for (int part = 0; part < numPartitionsPerColumn; part++) {
                    Map<String, int[]> delMap = pendingDeletions.get(batchColIdx).get(part);
                    Map<String, int[]> insMap = pendingInsertions.get(batchColIdx).get(part);

                    if (delMap.isEmpty() && insMap.isEmpty()) continue;

                    touchedBuckets += delMap.size() + insMap.size();
                    if (!isAborted && touchedBuckets > maxAllowedBuckets) {
                        isAborted = true;
                        tempInsertedValues.clear();
                        tempDeletedValues.clear();
                        tempInsertedValues = null;
                        tempDeletedValues = null;
                    }

                    SplitThreeLayersFilter filter = globalFilters.get(col).get(part);
                    long[] pureIoTracker = new long[1];

                    try {
                        int[] stats = filter.updateBatch(delMap, insMap, pureIoTracker, tempDeletedValues, tempInsertedValues);
                        totalDeletedDistinctCount += stats[0];
                        totalInsertedDistinctCount += stats[1];
                    } catch (IOException e) {
                        throw new RuntimeException("Fail to update fliters: column " + col + ", partition " + part, e);
                    }

                    delMap.clear();
                    insMap.clear();
                }

                deleteStatusList.add(totalDeletedDistinctCount);
                insertStatusList.add(totalInsertedDistinctCount);
                insertedValuesList.add(tempInsertedValues);
                deletedValuesList.add(tempDeletedValues);

                long currentTotalDistinct = 0;
                for (int p = 0; p < numPartitionsPerColumn; p++) {
                    currentTotalDistinct += globalFilters.get(col).get(p).getDistinctCount();
                }
                this.thresholds[col] = (int) (currentTotalDistinct * (violate_per / 10000.0F));
            }

            pendingDeletions.clear();
            pendingInsertions.clear();
            System.gc();
        }
    }


    private String resolveFilePathByIndex(int index) throws AlgorithmExecutionException {
        if (index >= configuration.getRelationalInputGenerators().size()) {
            throw new AlgorithmExecutionException("Cannot find corresponding input stream configuration, index out of bounds: " + index);
        }

        de.metanome.algorithm_integration.input.RelationalInputGenerator gen =
                configuration.getRelationalInputGenerators().get(index);

        try {
            if (gen.getClass().getName().contains("FileInputGenerator")) {
                java.lang.reflect.Method getFileMethod = gen.getClass().getMethod("getInputFile");
                File inputFile = (File) getFileMethod.invoke(gen);
                return inputFile.getAbsolutePath();
            } else {
                throw new AlgorithmExecutionException("Input source at " + index + " is not a physical file, type: " + gen.getClass().getSimpleName());
            }
        } catch (Exception e) {
            throw new AlgorithmExecutionException("Failed to retrieve physical file path using reflection: " + index, e);
        }
    }

    private void loadDataIntoContainers(int generatorIndex, List<List<Map<String, int[]>>> targetContainers, int startCol, int endCol) throws AlgorithmExecutionException {
        String filePath = resolveFilePathByIndex(generatorIndex);

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath), 512 * 1024)) {
            String line;
            boolean isFirstLine = true;

            while (true) {
                line = reader.readLine();
                if (line == null) {
                    break;
                }

                if (isFirstLine) {
                    isFirstLine = false;
                    continue;
                }

                int currentCol = 0;
                boolean inQuotes = false;
                boolean escaped = false;
                int valStart = 0;
                int len = line.length();

                for (int i = 0; i <= len; i++) {
                    char c = (i < len) ? line.charAt(i) : ',';
                    if (escaped) {
                        escaped = false;
                        continue;
                    }

                    if (c == '\\') {
                        escaped = true;
                    } else if (c == '\"') {
                        inQuotes = !inQuotes;
                    } else if (c == ',' && !inQuotes) {

                        if (currentCol >= startCol && currentCol < endCol) {
                            int start = valStart;
                            int end = i;

                            while (start < end && Character.isWhitespace(line.charAt(start))) {
                                start++;
                            }

                            if (start < end && line.charAt(start) == '\"' && line.charAt(end - 1) == '\"') {
                                start++;
                                end--;
                            }

                            if (start < end) {
                                String val = line.substring(start, end);

                                if (val.isEmpty() || val.equalsIgnoreCase("null")){

                                } else {
                                    int partitionId = Math.abs(val.hashCode() % numPartitionsPerColumn);
                                    Map<String, int[]> freqMap = targetContainers.get(currentCol - startCol).get(partitionId);

                                    int[] count = freqMap.get(val);
                                    if (count == null) {
                                        freqMap.put(val, new int[]{1});
                                    } else {
                                        count[0]++;
                                    }
                                }
                            }
                        }

                        currentCol++;
                        valStart = i + 1;
                        if (currentCol >= endCol) break;
                    }
                }
            }
        } catch (Exception e) {
            throw new AlgorithmExecutionException("Fail to load file, Index: " + generatorIndex + ", Path: " + filePath, e);
        }
    }

    private void processDeletionData() throws AlgorithmExecutionException {
        deleteStatusList.clear();
        insertStatusList.clear();
        insertedValuesList.clear();
        deletedValuesList.clear();

        int maxAllowedBuckets = (int) (numPartitionsPerColumn * filtersize * (this.microProbingThreshold / 10000.0D));

        for (int b = 0; b < numColumns; b += batchSize) {
            int endCol = Math.min(b + batchSize, numColumns);
            int currentBatchSize = endCol - b;

            List<List<Map<String, int[]>>> pendingDeletions = new ArrayList<>(currentBatchSize);
            for (int i = 0; i < currentBatchSize; i++) {
                List<Map<String, int[]>> byPartition = new ArrayList<>(numPartitionsPerColumn);
                for (int p = 0; p < numPartitionsPerColumn; p++) {
                    byPartition.add(new HashMap<>(16384));
                }
                pendingDeletions.add(byPartition);
            }

            loadDataIntoContainers(1, pendingDeletions, b, endCol);

            for (int col = b; col < endCol; col++) {
                int batchColIdx = col - b;
                int totalDeletedDistinctCount = 0;

                Set<String> tempDeletedValues = new HashSet<>();
                Set<String> tempInsertedValues = null;
                int touchedBuckets = 0;
                boolean isAborted = false;

                for (int part = 0; part < numPartitionsPerColumn; part++) {
                    Map<String, int[]> delMap = pendingDeletions.get(batchColIdx).get(part);

                    if (delMap.isEmpty()) continue;

                    touchedBuckets += delMap.size();
                    if (!isAborted && touchedBuckets > maxAllowedBuckets) {
                        isAborted = true;
                        tempDeletedValues.clear();
                        tempDeletedValues = null;
                    }

                    SplitThreeLayersFilter filter = globalFilters.get(col).get(part);
                    long[] pureIoTracker = new long[1];
                    try {
                        int[] stats = filter.updateBatch(delMap, Collections.emptyMap(), pureIoTracker, tempDeletedValues, tempInsertedValues);
                        totalDeletedDistinctCount += stats[0];
                    } catch (IOException e) {
                        throw new RuntimeException("Fail to update filters, column: " + col + ", partition " + part, e);
                    }
                    delMap.clear();
                }

                deleteStatusList.add(totalDeletedDistinctCount);
                insertStatusList.add(0);
                insertedValuesList.add(tempInsertedValues);
                deletedValuesList.add(tempDeletedValues);

                long currentTotalDistinct = 0;
                for (int part = 0; part < numPartitionsPerColumn; part++) {
                    currentTotalDistinct += globalFilters.get(col).get(part).getDistinctCount();
                }
                this.thresholds[col] = (int) (currentTotalDistinct * (violate_per / 10000.0F));
            }

            pendingDeletions.clear();
            System.gc();
        }
    }

    private void processInsertionData() throws AlgorithmExecutionException {
        deleteStatusList.clear();
        insertStatusList.clear();
        insertedValuesList.clear();
        deletedValuesList.clear();

        int maxAllowedBuckets = (int) (numPartitionsPerColumn * filtersize * (this.microProbingThreshold / 10000.0D));

        for (int b = 0; b < numColumns; b += batchSize) {
            int endCol = Math.min(b + batchSize, numColumns);
            int currentBatchSize = endCol - b;

            List<List<Map<String, int[]>>> pendingInsertions = new ArrayList<>(currentBatchSize);
            for (int i = 0; i < currentBatchSize; i++) {
                List<Map<String, int[]>> byPartition = new ArrayList<>(numPartitionsPerColumn);
                for (int p = 0; p < numPartitionsPerColumn; p++) {
                    byPartition.add(new HashMap<>(16384));
                }
                pendingInsertions.add(byPartition);
            }

            loadDataIntoContainers(1, pendingInsertions, b, endCol);

            for (int col = b; col < endCol; col++) {
                int batchColIdx = col - b;
                int totalInsertedDistinctCount = 0;

                Set<String> tempInsertedValues = new HashSet<>();
                Set<String> tempDeletedValues = null;
                int touchedBuckets = 0;
                boolean isAborted = false;

                for (int part = 0; part < numPartitionsPerColumn; part++) {
                    Map<String, int[]> insMap = pendingInsertions.get(batchColIdx).get(part);

                    if (insMap.isEmpty()) continue;

                    touchedBuckets += insMap.size();
                    if (!isAborted && touchedBuckets > maxAllowedBuckets) {
                        isAborted = true;
                        tempInsertedValues.clear();
                        tempInsertedValues = null;
                    }

                    SplitThreeLayersFilter filter = globalFilters.get(col).get(part);
                    long[] pureIoTracker = new long[1];

                    try {
                        int[] stats = filter.updateBatch(Collections.emptyMap(), insMap, pureIoTracker, tempDeletedValues, tempInsertedValues);
                        totalInsertedDistinctCount += stats[1];
                    } catch (IOException e) {
                        throw new RuntimeException("Fail to update filters, column: " + col + ", partition " + part, e);
                    }

                    insMap.clear();
                }

                deleteStatusList.add(0);
                insertStatusList.add(totalInsertedDistinctCount);
                insertedValuesList.add(tempInsertedValues);
                deletedValuesList.add(tempDeletedValues);

                long currentTotalDistinct = 0;
                for (int part = 0; part < numPartitionsPerColumn; part++) {
                    currentTotalDistinct += globalFilters.get(col).get(part).getDistinctCount();
                }
                this.thresholds[col] = (int) (currentTotalDistinct * (violate_per / 10000.0F));
            }

            pendingInsertions.clear();
            System.gc();
        }
    }

    private int countViolations(Set<String> pureValues, int rightCol) {
        if (pureValues == null || pureValues.isEmpty()) return 0;
        int violations = 0;

        for (String val : pureValues) {
            int part = Math.abs(val.hashCode() % numPartitionsPerColumn);
            SplitThreeLayersFilter rightFilter = globalFilters.get(rightCol).get(part);

            int h = val.hashCode();
            int hashIdx = (h ^ (h >>> 16)) & (rightFilter.getFilterSize() - 1);

            if (!rightFilter.getLayer1().get(hashIdx)) {
                violations++;
            } else {
                Set<String> rightValues = getCachedLayer3(rightFilter.getLayer3DiskPath());
                if (!rightValues.contains(val)) {
                    violations++;
                }
            }
        }
        return violations;
    }

    private int[] updateMatrix(int[][] matrix, long[][] totalVioMatrix, List<Integer> insertStatusList, List<Integer> deleteStatusList) {
        int numColumns = insertStatusList.size();
        double currentEpsilon = violate_per / 10000.0D;

        int microPrunedValid = 0;
        int microPrunedInvalid = 0;

        for (int i = 0; i < numColumns; i++) {
            for (int j = 0; j < numColumns; j++) {
                if (i == j) continue;
                int currentVal = matrix[i][j];
                int lhsIns = insertStatusList.get(i);
                int rhsIns = insertStatusList.get(j);
                int lhsDel = deleteStatusList.get(i);
                int rhsDel = deleteStatusList.get(j);

                if (currentVal == 1) {
                    if (lhsDel != 0 || rhsDel != 0 || lhsIns != 0) {
                        long low = totalVioMatrix[i][j] + rhsDel + lhsIns;
                        if (low > thresholds[i]) {
                            Set<String> lhsInsSet = insertedValuesList.get(i);
                            Set<String> lhsDelSet = deletedValuesList.get(i);

                            if (lhsInsSet != null && lhsDelSet != null && rhsDel == 0) {
                                int p_ins = lhsInsSet.size();
                                int p_del = lhsDelSet.size();
                                int q_ins = countViolations(lhsInsSet, j);
                                int q_del = countViolations(lhsDelSet, j);

                                if (q_ins - q_del <= (p_ins - p_del) * currentEpsilon) {
                                    totalVioMatrix[i][j] = totalVioMatrix[i][j] + q_ins - q_del;
                                    microPrunedValid++;
                                    continue;
                                }
                            }
                            currentVal = -1;
                        } else totalVioMatrix[i][j] = low;
                    }
                } else if (currentVal == 0) {
                    if (lhsDel != 0 || lhsIns != 0 || rhsIns != 0) {
                        long up = totalVioMatrix[i][j] - lhsDel - rhsIns;
                        if (up <= thresholds[i]) {
                            Set<String> lhsInsSet = insertedValuesList.get(i);
                            Set<String> lhsDelSet = deletedValuesList.get(i);

                            if (lhsInsSet != null && lhsDelSet != null && rhsIns == 0) {
                                int p_ins = lhsInsSet.size();
                                int p_del = lhsDelSet.size();
                                int q_ins = countViolations(lhsInsSet, j);
                                int q_del = countViolations(lhsDelSet, j);

                                if (q_ins - q_del >= (p_ins - p_del) * currentEpsilon) {
                                    totalVioMatrix[i][j] = totalVioMatrix[i][j] + q_ins - q_del;

                                    microPrunedInvalid++;
                                    continue;
                                }
                            }
                            currentVal = -1;
                        } else totalVioMatrix[i][j] = up;
                    }
                }
                matrix[i][j] = currentVal;
            }
        }

        return new int[]{microPrunedValid, microPrunedInvalid};
    }


    private final Map<String, Set<String>> layer3Cache = new LinkedHashMap<String, Set<String>>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Set<String>> eldest) {
            return size() > 128;
        }
    };

    private Set<String> getCachedLayer3(String filePath) {
        if (filePath == null || filePath.isEmpty()) return Collections.emptySet();

        Set<String> values = layer3Cache.get(filePath);

        if (values == null) {
            values = loadLayer3ToMemory(filePath);
            layer3Cache.put(filePath, values);
        }
        return values;
    }

    private void validateDirtyCandidates(int[][] matrix) {
        int numColumns = matrix.length;

        int initialDirtyCount = 0;
        int roughPrunedCount = 0;

        List<List<Integer>> activeCandidates = new ArrayList<>(numColumns);
        for (int i = 0; i < numColumns; i++) {
            List<Integer> cands = new ArrayList<>();
            for (int j = 0; j < numColumns; j++) {
                if (i != j && matrix[i][j] == -1) {
                    cands.add(j);
                    initialDirtyCount++;
                }
            }
            activeCandidates.add(cands);
        }

        for (int i = 0; i < numColumns; i++) {
            if (!activeCandidates.get(i).isEmpty()) {
                roughPrunedCount += roughFilterOneRow(i, activeCandidates.get(i), matrix);
            }
        }

        int fineScreenedCount = initialDirtyCount - roughPrunedCount;

        long[] totalLeftCount = new long[numColumns];
        long[] checkedLeftCount = new long[numColumns];
        for (int i = 0; i < numColumns; i++) {
            for (int p = 0; p < numPartitionsPerColumn; p++) {
                totalLeftCount[i] += globalFilters.get(i).get(p).getDistinctCount();
            }
        }
        long[][] currentViolations = new long[numColumns][numColumns];

        for (int p = 0; p < numPartitionsPerColumn; p++) {
            if (fineScreenedCount == 0) break;

            System.out.println("Validating the No." + (p+1) + "/" +  numPartitionsPerColumn + "patition");
            layer3Cache.clear();

            for (int i = 0; i < numColumns; i++) {
                List<Integer> rightCols = activeCandidates.get(i);
                if (rightCols.isEmpty()) continue;

                SplitThreeLayersFilter leftFilter = globalFilters.get(i).get(p);
                Set<String> leftValues = getCachedLayer3(leftFilter.getLayer3DiskPath());

                checkedLeftCount[i] += leftValues.size();

                if (leftValues.isEmpty()) continue;

                Iterator<Integer> it = rightCols.iterator();
                while (it.hasNext()) {
                    int j = it.next();

                    SplitThreeLayersFilter rightFilter = globalFilters.get(j).get(p);
                    Set<String> rightValues = getCachedLayer3(rightFilter.getLayer3DiskPath());

                    for (String val : leftValues) {
                        if (!rightValues.contains(val)) {
                            currentViolations[i][j]++;

                            if (currentViolations[i][j] > thresholds[i]) {
                                matrix[i][j] = 0;
                                totalVioMatrix[i][j] = currentViolations[i][j];
                                it.remove();
                                break;
                            }
                        }
                    }
                }

                it = rightCols.iterator();
                while (it.hasNext()) {
                    int j = it.next();
                    long remainingPossibleVios = totalLeftCount[i] - checkedLeftCount[i];
                    if (currentViolations[i][j] + remainingPossibleVios <= thresholds[i]) {
                        matrix[i][j] = 1;
                        totalVioMatrix[i][j] = currentViolations[i][j];
                        it.remove();
                    }
                }
            }
            System.gc();
        }

        for (int i = 0; i < numColumns; i++) {
            for (int j : activeCandidates.get(i)) {
                matrix[i][j] = 1;
                totalVioMatrix[i][j] = currentViolations[i][j];
            }
        }
    }

    private int roughFilterOneRow(int leftCol, List<Integer> candidates, int[][] matrix) {
        if (candidates.isEmpty()) return 0;

        long threshold = thresholds[leftCol];
        int prunedCount = 0;

        Iterator<Integer> it = candidates.iterator();
        while (it.hasNext()) {
            int j = it.next();
            long lowerBoundViolations = 0;

            for (int p = 0; p < numPartitionsPerColumn; p++) {
                SplitThreeLayersFilter leftFilter = globalFilters.get(leftCol).get(p);
                SplitThreeLayersFilter rightFilter = globalFilters.get(j).get(p);

                BitSet l1_LHS = leftFilter.getLayer1();
                BitSet l1_RHS = rightFilter.getLayer1();
                int[] l2_LHS = leftFilter.getLayer2();
                int[] l2_RHS = rightFilter.getLayer2();
                int filterSize = leftFilter.getFilterSize();

                for (int b = 0; b < filterSize; b++) {
                    boolean lhsBit = l1_LHS.get(b);
                    boolean rhsBit = l1_RHS.get(b);

                    if (lhsBit) {
                        if (!rhsBit) {
                            lowerBoundViolations += l2_LHS[b];
                        } else if (l2_LHS[b] > l2_RHS[b]) {
                            lowerBoundViolations += (l2_LHS[b] - l2_RHS[b]);
                        }
                    }

                    if (lowerBoundViolations > threshold) break;
                }
                if (lowerBoundViolations > threshold) break;
            }

            if (lowerBoundViolations > threshold) {
                matrix[leftCol][j] = 0;
                totalVioMatrix[leftCol][j] = lowerBoundViolations;
                it.remove();
                prunedCount++;
            }
        }
        return prunedCount;
    }

    private void verifyOneRow(int leftCol, List<Integer> rightCols, int[][] matrix) {
        long threshold = thresholds[leftCol];
        int numParts = numPartitionsPerColumn;

        long[] violations = new long[numColumns];
        boolean[] active = new boolean[numColumns];
        for (int j : rightCols) active[j] = true;

        long totalLeftCount = 0;
        for (int p = 0; p < numPartitionsPerColumn; p++) {
            totalLeftCount += globalFilters.get(leftCol).get(p).getDistinctCount();
        }

        long checkedCount = 0;

        for (int p = 0; p < numParts; p++) {
            SplitThreeLayersFilter leftFilter = globalFilters.get(leftCol).get(p);

            Set<String> leftValues = getCachedLayer3(leftFilter.getLayer3DiskPath());
            if (leftValues.isEmpty()) continue;

            for (int j : rightCols) {
                if (!active[j]) continue;

                SplitThreeLayersFilter rightFilter = globalFilters.get(j).get(p);
                Set<String> rightValues = getCachedLayer3(rightFilter.getLayer3DiskPath());

                for (String val : leftValues) {
                    if (!rightValues.contains(val)) {
                        violations[j]++;

                        if (violations[j] > threshold) {
                            active[j] = false;
                            matrix[leftCol][j] = 0;
                            this.totalVioMatrix[leftCol][j] = violations[j];
                            break;
                        }
                    }
                }
            }

            checkedCount += leftValues.size();

            for (int j : rightCols) {
                if (active[j] && (violations[j] + (totalLeftCount - checkedCount) <= threshold)) {
                    active[j] = false;
                    matrix[leftCol][j] = 1;
                    this.totalVioMatrix[leftCol][j] = violations[j];
                }
            }

            boolean anyActive = false;
            for (int j : rightCols) if (active[j]) anyActive = true;
            if (!anyActive) break;
        }

        for (int j : rightCols) {
            if (active[j]) {
                matrix[leftCol][j] = 1;
                this.totalVioMatrix[leftCol][j] = violations[j];
            }
        }
    }

    private Set<String> loadLayer3ToMemory(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            return Collections.emptySet();
        }

        Set<String> values = new HashSet<>();

        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 64 * 1024))) {
            while (true) {
                int hashIdx = dis.readInt();
                int bucketSize = dis.readInt();

                for (int i = 0; i < bucketSize; i++) {
                    short len = dis.readShort();
                    byte[] bytes = new byte[len];
                    dis.readFully(bytes);
                    dis.readInt();
                    values.add(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        } catch (EOFException e) {
        } catch (IOException e) {
            e.printStackTrace();
        }

        return values;
    }


    public static class SplitThreeLayersFilter {

        private BitSet layer1_BitSet;
        private int[] layer2_Counts;
        private String layer3_DiskPath;
        private int filterSize;
        private long totalDistinctCount = 0;

        @Override
        public String toString() {
            return "SplitThreeLayersFilter{diskPath='" + layer3_DiskPath + "', filterSize=" + filterSize + "}";
        }

        public BitSet getLayer1() {
            return layer1_BitSet;
        }

        public int[] getLayer2() {
            return layer2_Counts;
        }

        public int getFilterSize() {
            return filterSize;
        }

        public String getLayer3DiskPath() {
            return layer3_DiskPath;
        }

        public SplitThreeLayersFilter(int filterSize, String diskPath) {
            this.filterSize = filterSize;
            this.layer3_DiskPath = diskPath;
            this.layer1_BitSet = new BitSet(filterSize);
            this.layer2_Counts = new int[filterSize];

            String layersFilePath = diskPath + "_layers.dat";
            // System.out.println("DEBUG: Attempting to load layers from: " +
            // layersFilePath);
            File layersFile = new File(layersFilePath);
            if (layersFile.exists()) {
                try (DataInputStream dis = new DataInputStream(new FileInputStream(layersFile))) {
                    int bitSetLen = dis.readInt();
                    byte[] bitSetBytes = new byte[bitSetLen];
                    dis.readFully(bitSetBytes);
                    this.layer1_BitSet = BitSet.valueOf(bitSetBytes);
                    // System.out.println("DEBUG: Loaded BitSet, length=" + bitSetLen + ",
                    // cardinality="
                    // + layer1_BitSet.cardinality());

                    int arrayLen = dis.readInt();
                    this.layer2_Counts = new int[arrayLen];
                    this.totalDistinctCount = 0;
                    for (int i = 0; i < arrayLen; i++) {
                        this.layer2_Counts[i] = dis.readInt();
                        this.totalDistinctCount += this.layer2_Counts[i];
                    }
                    // System.out.println("DEBUG: Loaded count array, length=" + arrayLen + ", first
                    // few: " +
                    // (arrayLen > 0 ? layer2_Counts[0] + ", " + (arrayLen > 1 ? layer2_Counts[1] :
                    // "") : ""));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else {
                System.out.println("DEBUG: Layers file not found: " + layersFilePath + ", starting with empty filter.");
            }
        }

        public long getDistinctCount() {
            return this.totalDistinctCount;
        }

        private boolean isNumeric(String str) {
            for (int i = 0; i < str.length(); i++) {
                if (!Character.isDigit(str.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        public int[] updateBatch(Map<String, int[]> delMap, Map<String, int[]> insMap, long[] ioTracker, Set<String> collectedDel, Set<String> collectedIns) throws IOException {
            int disappearedCount = 0;
            int addedCount = 0;

            File oldFile = new File(layer3_DiskPath);
            File tempFile = new File(layer3_DiskPath + ".tmp");
            boolean oldExists = oldFile.exists();

            Map<Integer, Map<String, int[]>> delByHash = groupByHash(delMap);
            Map<Integer, Map<String, int[]>> insByHash = groupByHash(insMap);

            if (!oldExists) {
                oldFile.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(oldFile);
                BufferedOutputStream bos = new BufferedOutputStream(fos);
                DataOutputStream dos = new DataOutputStream(bos);

                try {
                    for (Map.Entry<Integer, Map<String, int[]>> entry : insByHash.entrySet()) {
                        int h = entry.getKey();
                        Map<String, Integer> valMap = convertToIntegerMap(entry.getValue());

                        if (collectedIns != null) collectedIns.addAll(valMap.keySet());

                        writeBinaryBucket(dos, h, valMap, ioTracker);
                        this.layer2_Counts[h] = valMap.size();
                        this.layer1_BitSet.set(h, true);
                        addedCount += valMap.size();
                    }
                } finally {
                    dos.close();
                }
                this.totalDistinctCount += addedCount;
                return new int[]{0, addedCount};
            }

            DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(oldFile)));
            DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tempFile)));

            try {
                try {
                    while (true) {
                        int h = dis.readInt();
                        int size = dis.readInt();

                        Map<String, Integer> valMap = new HashMap<>();
                        for (int i = 0; i < size; i++) {
                            short len = dis.readShort();
                            byte[] bytes = new byte[len];
                            dis.readFully(bytes);
                            int oldFreq = dis.readInt();

                            valMap.put(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), oldFreq);
                        }

                        if (delByHash.containsKey(h)) {
                            Map<String, int[]> toDel = delByHash.remove(h);
                            for (Map.Entry<String, int[]> delEntry : toDel.entrySet()) {
                                String val = delEntry.getKey();
                                int freq = delEntry.getValue()[0];

                                if (valMap.containsKey(val)) {
                                    int newC = valMap.get(val) - freq;
                                    if (newC <= 0) {
                                        valMap.remove(val);
                                        disappearedCount++;
                                        if (collectedDel != null) collectedDel.add(val);
                                    } else {
                                        valMap.put(val, newC);
                                    }
                                }
                            }
                        }

                        if (insByHash.containsKey(h)) {
                            Map<String, int[]> toIns = insByHash.remove(h);
                            for (Map.Entry<String, int[]> insEntry : toIns.entrySet()) {
                                String val = insEntry.getKey();
                                int freq = insEntry.getValue()[0];

                                if (!valMap.containsKey(val)) {
                                    addedCount++;
                                    if (collectedIns != null) collectedIns.add(val);
                                }
                                valMap.put(val, valMap.getOrDefault(val, 0) + freq);
                            }
                        }

                        if (valMap.isEmpty()) {
                            this.layer2_Counts[h] = 0;
                            this.layer1_BitSet.set(h, false);
                        } else {
                            this.layer2_Counts[h] = valMap.size();
                            this.layer1_BitSet.set(h, true);
                            writeBinaryBucket(dos, h, valMap, ioTracker);
                        }
                    }
                } catch (EOFException e) {
                }

                for (Map.Entry<Integer, Map<String, int[]>> entry : insByHash.entrySet()) {
                    int h = entry.getKey();
                    Map<String, Integer> valMap = convertToIntegerMap(entry.getValue());
                    if (collectedIns != null) {
                        collectedIns.addAll(valMap.keySet());
                    }
                    writeBinaryBucket(dos, h, valMap, ioTracker);
                    this.layer2_Counts[h] = valMap.size();
                    this.layer1_BitSet.set(h, true);
                    addedCount += valMap.size();
                }
            } finally {
                dis.close();
                dos.close();
            }

            if (oldFile.delete()) tempFile.renameTo(oldFile);
            this.totalDistinctCount = this.totalDistinctCount - disappearedCount + addedCount;
            return new int[]{disappearedCount, addedCount};
        }

        public void saveLayersToDisk() {
            String layersFilePath = this.layer3_DiskPath + "_layers.dat";
            try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(layersFilePath))) {
                byte[] bitSetBytes = this.layer1_BitSet.toByteArray();
                dos.writeInt(bitSetBytes.length);
                dos.write(bitSetBytes);

                dos.writeInt(this.layer2_Counts.length);
                for (int count : this.layer2_Counts) {
                    dos.writeInt(count);
                }
            } catch (IOException e) {
                System.err.println("Fail to save Layers to Disk: " + layersFilePath);
                e.printStackTrace();
            }
        }

        private void writeBinaryBucket(DataOutputStream dos, int h, Map<String, Integer> valMap, long[] ioTracker) throws IOException {
            dos.writeInt(h);
            dos.writeInt(valMap.size());
            for (Map.Entry<String, Integer> entry : valMap.entrySet()) {
                byte[] bytes = entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                dos.writeShort((short) bytes.length);
                dos.write(bytes);
                dos.writeInt(entry.getValue());
            }
        }

        private Map<Integer, Map<String, int[]>> groupByHash(Map<String, int[]> map) {
            Map<Integer, Map<String, int[]>> result = new HashMap<>();
            if (map != null) {
                for (Map.Entry<String, int[]> entry : map.entrySet()) {
                    int h = entry.getKey().hashCode();
                    int hash = (h ^ (h >>> 16)) & (filterSize - 1);
                    result.computeIfAbsent(hash, k -> new HashMap<>()).put(entry.getKey(), entry.getValue());
                }
            }
            return result;
        }

        private Map<String, Integer> convertToIntegerMap(Map<String, int[]> map) {
            Map<String, Integer> result = new HashMap<>();
            for (Map.Entry<String, int[]> entry : map.entrySet()) {
                result.put(entry.getKey(), entry.getValue()[0]);
            }
            return result;
        }

        private void writeLine(BufferedWriter writer, int h, Map<String, Integer> valMap, long[] ioTracker) throws IOException {
            writer.write(h + "\t");
            boolean first = true;
            for (Map.Entry<String, Integer> vEntry : valMap.entrySet()) {
                if (!first) writer.write(";");
                writer.write(vEntry.getKey() + ":" + vEntry.getValue());
                first = false;
            }
            writer.newLine();
        }
    }
}
