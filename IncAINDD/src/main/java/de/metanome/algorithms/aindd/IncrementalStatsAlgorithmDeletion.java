package de.metanome.algorithms.aindd;

import de.metanome.algorithm_integration.AlgorithmConfigurationException;
import de.metanome.algorithm_integration.AlgorithmExecutionException;
import de.metanome.algorithm_integration.ColumnIdentifier;
import de.metanome.algorithm_integration.ColumnPermutation;
import de.metanome.algorithm_integration.input.InputGenerationException;
import de.metanome.algorithm_integration.input.InputIterationException;
import de.metanome.algorithm_integration.input.RelationalInput;
import de.metanome.algorithm_integration.input.RelationalInputGenerator;
import de.metanome.algorithm_integration.results.InclusionDependency;
import de.metanome.algorithms.aindd.config.Configuration;
import de.uni_potsdam.utils.FileUtils;

import java.io.*;
import java.util.*;


public class IncrementalStatsAlgorithmDeletion {

    protected Configuration configuration;
    protected List<String> columnNames;
    protected int numColumns;
    protected int numPartitionsPerColumn;
    protected int filtersize;
    protected int violate_per;
    protected int batchSize;
    protected int microProbingThreshold;

    protected List<List<SplitThreeLayersFilter>> globalFilters = null;
    protected String tempFolderPath = "AINDD_temp2";
    protected boolean dataDeletion = false;
    protected boolean dataInsertion = false;
    protected int[][] oldMatrix;
    protected String[] tableNames = null;
    protected List<Integer> insertStatusList = new ArrayList<>();
    protected List<Integer> deleteStatusList = new ArrayList<>();
    protected List<Map<String, Integer>> insertedValuesList = new ArrayList<>();
    protected List<Map<String, Integer>> deletedValuesList = new ArrayList<>();
    protected long[] thresholds;
    protected long[][] totalVioMatrix;
    protected int[] rowCountOfColumn;

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
                    InclusionDependency ind = new InclusionDependency(new ColumnPermutation(leftCol), new ColumnPermutation(rightCol));
                    results.add(ind);
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
            this.rowCountOfColumn = (int[]) ois.readObject();
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
            oos.writeObject(this.rowCountOfColumn);
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
                int totalDeletedFreq = 0;
                int totalInsertedCount = 0;

                Map<String, Integer> tempInsertedValues = new HashMap<>();
                Map<String, Integer> tempDeletedValues = new HashMap<>();
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

                        totalDeletedFreq += stats[0];
                        totalInsertedCount += stats[1];
                    } catch (IOException e) {
                        throw new RuntimeException("Fail to update fliters: column " + col + ", partition " + part, e);
                    }

                    delMap.clear();
                    insMap.clear();
                }

                deleteStatusList.add(totalDeletedFreq);
                insertStatusList.add(totalInsertedCount);
                insertedValuesList.add(tempInsertedValues);
                deletedValuesList.add(tempDeletedValues);

                this.rowCountOfColumn[col] = this.rowCountOfColumn[col] - totalDeletedFreq + totalInsertedCount;
                this.thresholds[col] = (long) (this.rowCountOfColumn[col] * (violate_per / 10000.0F));
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

        RelationalInputGenerator gen =
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

                                if (val.isEmpty() || val.equalsIgnoreCase("null"))  {
                                    // is null
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
                int totalDeletedCount = 0;

                Map<String, Integer> tempDeletedValues = new HashMap<>();
                Map<String, Integer> tempInsertedValues = null;
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
                        totalDeletedCount += stats[0];
                    } catch (IOException e) {
                        throw new RuntimeException("Fail to update filters, column: " + col + ", partition " + part, e);
                    }

                    delMap.clear();
                }

                deleteStatusList.add(totalDeletedCount);
                insertStatusList.add(0);
                insertedValuesList.add(tempInsertedValues);
                deletedValuesList.add(tempDeletedValues);

                this.rowCountOfColumn[col] = this.rowCountOfColumn[col] - totalDeletedCount;
                this.thresholds[col] = (long) (this.rowCountOfColumn[col] * (violate_per / 10000.0F));
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
                int totalInsertedCount = 0;

                Map<String, Integer> tempInsertedValues = new HashMap<>();
                Map<String, Integer> tempDeletedValues = null;
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
                        totalInsertedCount += stats[1];
                    } catch (IOException e) {
                        throw new RuntimeException("Fail to update filters, column: " + col + ", partition " + part, e);
                    }

                    insMap.clear();
                }

                deleteStatusList.add(0);
                insertStatusList.add(totalInsertedCount);

                insertedValuesList.add(tempInsertedValues);
                deletedValuesList.add(tempDeletedValues);

                this.rowCountOfColumn[col] = this.rowCountOfColumn[col] + totalInsertedCount;
                this.thresholds[col] = (long) (this.rowCountOfColumn[col] * (violate_per / 10000.0F));
            }

            pendingInsertions.clear();
            System.gc();
        }
    }

    private long countViolationFreqs(Map<String, Integer> pureValues, int rightCol) {
        if (pureValues == null || pureValues.isEmpty()) return 0;
        long violations = 0;

        for (Map.Entry<String, Integer> entry : pureValues.entrySet()) {
            String val = entry.getKey();
            int freq = entry.getValue();

            int part = Math.abs(val.hashCode() % numPartitionsPerColumn);
            SplitThreeLayersFilter rightFilter = globalFilters.get(rightCol).get(part);

            int h = val.hashCode();
            int hashIdx = (h ^ (h >>> 16)) & (rightFilter.getFilterSize() - 1);

            if (!rightFilter.getLayer1().get(hashIdx)) {
                violations += freq;
            } else {
                Map<String, Integer> rightValues = getCachedLayer3(rightFilter.getLayer3DiskPath());
                if (!rightValues.containsKey(val)) {
                    violations += freq;
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
                if (currentVal == -1) continue;

                long lhsIns = insertStatusList.get(i);
                long rhsIns = insertStatusList.get(j);
                long lhsDel = deleteStatusList.get(i);
                long rhsDel = deleteStatusList.get(j);

                if (currentVal == 1) {
                    if (rhsDel == 0) {
                        long maxPossibleVio = totalVioMatrix[i][j] + lhsIns;
                        if (maxPossibleVio <= thresholds[i]) {
                            currentVal = 1;
                            totalVioMatrix[i][j] = maxPossibleVio;
                        } else {
                            Map<String, Integer> lhsInsMap = insertedValuesList.get(i);
                            Map<String, Integer> lhsDelMap = deletedValuesList.get(i);

                            if (lhsInsMap != null && lhsDelMap != null && rhsDel == 0) {
                                long k_ins = lhsIns;
                                long k_del = lhsDel;
                                long q_ins = countViolationFreqs(lhsInsMap, j);
                                long q_del = countViolationFreqs(lhsDelMap, j);

                                if (q_ins - q_del <= (k_ins - k_del) * currentEpsilon) {
                                    totalVioMatrix[i][j] = totalVioMatrix[i][j] + q_ins - q_del;
                                    currentVal = 1;
                                    microPrunedValid++;
                                    matrix[i][j] = currentVal;
                                    continue;
                                }
                            }
                            currentVal = -1;
                        }
                    } else {
                        currentVal = -1;
                    }
                } else if (currentVal == 0) {
                    if (rhsIns == 0) {
                        long minPossibleVio = Math.max(0, totalVioMatrix[i][j] - lhsDel);
                        if (minPossibleVio > thresholds[i]) {
                            currentVal = 0;
                            totalVioMatrix[i][j] = minPossibleVio;
                        } else {
                            Map<String, Integer> lhsInsMap = insertedValuesList.get(i);
                            Map<String, Integer> lhsDelMap = deletedValuesList.get(i);

                            if (lhsInsMap != null && lhsDelMap != null && rhsIns == 0) {
                                long k_ins = lhsIns;
                                long k_del = lhsDel;
                                long q_ins = countViolationFreqs(lhsInsMap, j);
                                long q_del = countViolationFreqs(lhsDelMap, j);

                                if (q_ins - q_del >= (k_ins - k_del) * currentEpsilon) {
                                    totalVioMatrix[i][j] = totalVioMatrix[i][j] + q_ins - q_del;
                                    currentVal = 0;
                                    microPrunedInvalid++;
                                    matrix[i][j] = currentVal;
                                    continue;
                                }
                            }
                            currentVal = -1;
                        }
                    } else {
                        currentVal = -1;
                    }
                }
                matrix[i][j] = currentVal;
            }
        }
        return new int[]{microPrunedValid, microPrunedInvalid};
    }

    private final Map<String, Map<String, Integer>> layer3Cache = new LinkedHashMap<String, Map<String, Integer>>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Map<String, Integer>> eldest) {
            return size() > 128;
        }
    };

    private Map<String, Integer> getCachedLayer3(String filePath) {
        if (filePath == null || filePath.isEmpty()) return Collections.emptyMap();
        Map<String, Integer> values = layer3Cache.get(filePath);
        if (values == null) {
            values = loadLayer3ToMemory(filePath);
            layer3Cache.put(filePath, values);
        }
        return values;
    }

    private void validateDirtyCandidates(int[][] matrix) {
        int numColumns = matrix.length;

        List<List<Integer>> activeCandidates = new ArrayList<>(numColumns);
        for (int i = 0; i < numColumns; i++) {
            List<Integer> cands = new ArrayList<>();
            for (int j = 0; j < numColumns; j++) {
                if (i != j && matrix[i][j] == -1) {
                    cands.add(j);
                }
            }
            activeCandidates.add(cands);
        }

        for (int i = 0; i < numColumns; i++) {
            if (!activeCandidates.get(i).isEmpty()) {
                roughFilterOneRow(i, activeCandidates.get(i), matrix);
            }
        }

        long[] totalLeftCount = new long[numColumns];
        long[] checkedLeftCount = new long[numColumns];
        for (int i = 0; i < numColumns; i++) {
            totalLeftCount[i] = this.rowCountOfColumn[i];
        }
        long[][] currentViolations = new long[numColumns][numColumns];

        for (int p = 0; p < numPartitionsPerColumn; p++) {

            System.out.println("Validating the No." + (p+1) + "/" +  numPartitionsPerColumn + "patition");
            layer3Cache.clear();

            for (int i = 0; i < numColumns; i++) {
                List<Integer> rightCols = activeCandidates.get(i);
                if (rightCols.isEmpty()) continue;

                SplitThreeLayersFilter leftFilter = globalFilters.get(i).get(p);
                Map<String, Integer> leftValues = getCachedLayer3(leftFilter.getLayer3DiskPath());

                if (leftValues.isEmpty()) continue;

                long leftTotalFreq = 0;
                for(int freq : leftValues.values()) leftTotalFreq += freq;
                checkedLeftCount[i] += leftTotalFreq;

                Iterator<Integer> it = rightCols.iterator();
                while (it.hasNext()) {
                    int j = it.next();

                    SplitThreeLayersFilter rightFilter = globalFilters.get(j).get(p);
                    Map<String, Integer> rightValues = getCachedLayer3(rightFilter.getLayer3DiskPath());

                    for (Map.Entry<String, Integer> entry : leftValues.entrySet()) {
                        if (!rightValues.containsKey(entry.getKey())) {
                            currentViolations[i][j] += entry.getValue();

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

    private void roughFilterOneRow(int leftCol, List<Integer> candidates, int[][] matrix) {
        if (candidates.isEmpty()) return;

        long threshold = thresholds[leftCol];

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

                int[] l2_LHS_MinFreqs = leftFilter.getLayer2MinFreqs();

                for (int b = 0; b < filterSize; b++) {
                    boolean lhsBit = l1_LHS.get(b);
                    boolean rhsBit = l1_RHS.get(b);

                    if (lhsBit) {
                        if (!rhsBit) {
                            lowerBoundViolations += (long) l2_LHS[b] * l2_LHS_MinFreqs[b];
                        } else if (l2_LHS[b] > l2_RHS[b]) {
                            lowerBoundViolations += (long) (l2_LHS[b] - l2_RHS[b]) * l2_LHS_MinFreqs[b];
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
            }
        }
    }

    private Map<String, Integer> loadLayer3ToMemory(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            return Collections.emptyMap();
        }

        Map<String, Integer> values = new HashMap<>();

        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 64 * 1024))) {
            while (true) {
                int hashIdx = dis.readInt();
                int bucketSize = dis.readInt();

                for (int i = 0; i < bucketSize; i++) {
                    short len = dis.readShort();
                    byte[] bytes = new byte[len];
                    dis.readFully(bytes);
                    int freq = dis.readInt();

                    values.put(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), freq);
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
        private int[] layer2_MinFreqs;
        public int[] getLayer2MinFreqs() { return layer2_MinFreqs; }

        private String layer3_DiskPath;
        private int filterSize;

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

                    int arrayLen = dis.readInt();
                    this.layer2_Counts = new int[arrayLen];
                    for (int i = 0; i < arrayLen; i++) {
                        this.layer2_Counts[i] = dis.readInt();
                    }

                    this.layer2_MinFreqs = new int[arrayLen];
                    if (dis.available() > 0) {
                        int minFreqLen = dis.readInt();
                        for (int i = 0; i < minFreqLen; i++) {
                            this.layer2_MinFreqs[i] = dis.readInt();
                        }
                    } else {
                        Arrays.fill(this.layer2_MinFreqs, 0);
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

        public int[] updateBatch(Map<String, int[]> delMap, Map<String, int[]> insMap, long[] ioTracker, Map<String, Integer> collectedDel, Map<String, Integer> collectedIns) throws IOException {
            int disappearedFreq = 0;
            int addedFreq = 0;

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

                        if (collectedIns != null) {
                            for (Map.Entry<String, Integer> vEntry : valMap.entrySet()) {
                                collectedIns.put(vEntry.getKey(), collectedIns.getOrDefault(vEntry.getKey(), 0) + vEntry.getValue());
                            }
                        }

                        writeBinaryBucket(dos, h, valMap, ioTracker);
                        this.layer2_Counts[h] = valMap.size();
                        this.layer1_BitSet.set(h, true);

                        int currentMinFreq = Integer.MAX_VALUE;
                        int bucketTotalFreq = 0;
                        for (int freq : valMap.values()) {
                            if (freq < currentMinFreq) currentMinFreq = freq;
                            bucketTotalFreq += freq;
                        }
                        this.layer2_MinFreqs[h] = currentMinFreq;
                        addedFreq += bucketTotalFreq;
                    }
                } finally {
                    dos.close();
                }
                return new int[]{0, addedFreq};
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
                                int delF = delEntry.getValue()[0];

                                if (valMap.containsKey(val)) {
                                    int oldF = valMap.get(val);
                                    int effectiveDelF = Math.min(oldF, delF);

                                    if (collectedDel != null) {
                                        collectedDel.put(val, collectedDel.getOrDefault(val, 0) + effectiveDelF);
                                    }

                                    int newC = oldF - delF;
                                    if (newC <= 0) {
                                        disappearedFreq += oldF;
                                        valMap.remove(val);
                                    } else {
                                        disappearedFreq += delF;
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
                                if (collectedIns != null) {
                                    collectedIns.put(val, collectedIns.getOrDefault(val, 0) + freq);
                                }
                                addedFreq += freq;
                                valMap.put(val, valMap.getOrDefault(val, 0) + freq);
                            }
                        }

                        if (valMap.isEmpty()) {
                            this.layer2_Counts[h] = 0;
                            this.layer2_MinFreqs[h] = 0;
                            this.layer1_BitSet.set(h, false);
                        } else {
                            this.layer2_Counts[h] = valMap.size();
                            this.layer1_BitSet.set(h, true);

                            int currentMinFreq = Integer.MAX_VALUE;
                            for (int freq : valMap.values()) {
                                if (freq < currentMinFreq) currentMinFreq = freq;
                            }
                            this.layer2_MinFreqs[h] = currentMinFreq;

                            writeBinaryBucket(dos, h, valMap, ioTracker);
                        }
                    }
                } catch (EOFException e) { }

                for (Map.Entry<Integer, Map<String, int[]>> entry : insByHash.entrySet()) {
                    int h = entry.getKey();
                    Map<String, Integer> valMap = convertToIntegerMap(entry.getValue());

                    if (collectedIns != null) {
                        for (Map.Entry<String, Integer> vEntry : valMap.entrySet()) {
                            String val = vEntry.getKey();
                            int freq = vEntry.getValue();
                            collectedIns.put(val, collectedIns.getOrDefault(val, 0) + freq);
                        }
                    }

                    writeBinaryBucket(dos, h, valMap, ioTracker);
                    this.layer2_Counts[h] = valMap.size();
                    this.layer1_BitSet.set(h, true);

                    int currentMinFreq = Integer.MAX_VALUE;
                    int bucketTotalFreq = 0;
                    for (int freq : valMap.values()) {
                        if (freq < currentMinFreq) currentMinFreq = freq;
                        bucketTotalFreq += freq;
                    }
                    this.layer2_MinFreqs[h] = currentMinFreq;
                    addedFreq += bucketTotalFreq;
                }
            } finally {
                dis.close();
                dos.close();
            }

            if (oldFile.delete()) tempFile.renameTo(oldFile);
            return new int[]{disappearedFreq, addedFreq};
        }

        public void saveLayersToDisk() {
            String layersFilePath = this.layer3_DiskPath + "_layers.dat";
            try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(layersFilePath)))) {

                byte[] bitSetBytes = this.layer1_BitSet.toByteArray();
                dos.writeInt(bitSetBytes.length);
                dos.write(bitSetBytes);

                dos.writeInt(this.layer2_Counts.length);
                for (int count : this.layer2_Counts) {
                    dos.writeInt(count);
                }

                dos.writeInt(this.layer2_MinFreqs.length);
                for (int minFreq : this.layer2_MinFreqs) {
                    dos.writeInt(minFreq);
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
