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
import de.metanome.algorithms.aindd.structures.ThreeLayersFilterDelete;
import de.metanome.algorithms.aindd.io.DiskBack;
import de.metanome.algorithms.aindd.structures.*;
import de.metanome.util.TableInfo;
import de.metanome.util.TableInfoFactory;
import de.uni_potsdam.utils.FileUtils;


public class AINDDAlgorithm {
	protected InclusionDependencyResultReceiver resultReceiver = null;
	protected List<String> columnNames;
    private TableInfoFactory tableInfoFactory;
    protected Configuration configuration;
	protected String[] tableNames = null;
	protected String tempFolderPath = "AINDD_temp";
	protected boolean cleanTemp = true;
	protected boolean deleteMode = false;
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
	protected List<List<int []>> bucketSizeRange;
	protected List<List<Boolean>> partition_backed = null;
	protected PriorityQueue<PartitionSize> maxPartitionHeap = null;
	protected PriorityQueue<PartitionSize> maxPartitionHeapCurr = null;
	protected int filterSize = 1024;
	protected int violate_per;
	protected List<List<ThreeLayersFilter>> filterInsert_list = null;
	protected boolean[] isNotFullMem;
	protected boolean[] isMemAndBuild;

	protected int[][] violationMatrix;
	protected Map<String,Integer> lineNameToIndex;
	protected int[] rowVioNum;
	protected int[] lineVioNum;
	protected boolean[] leftViolation;
	protected boolean[] rightViolation;
	protected boolean[] violation;
	protected int[][] columnThreshold;
	protected Integer[] bucketInMemPerPart;

	// things for deletion semantics
	protected List<List<ThreeLayersFilterDelete>> filterDelete_list = null;
	protected int[] rowCountOfTable;
	protected int[] tableThreshold;
	protected List<List<HashMap<String, Integer>>> partitions_delete = null;

	public void execute(Configuration configuration) throws AlgorithmExecutionException, SQLException {
        this.configuration = configuration;
		this.tableInfoFactory = new TableInfoFactory();
		List<TableInfo> tables = this.tableInfoFactory.create(configuration.getRelationalInputGenerators(), configuration.getTableInputGenerators());
		// THE DISCOVERY ALGORITHM

		this.initializeCommon(tables);
		if (!this.deleteMode) {
			this.initializeInsertion(tables);
			partitioningInsertion(tables);
			List<InclusionDependency> results;
			try {
				results = this.AINDDiscovery();
			} catch (SQLException e) {
				throw new RuntimeException(e);
			} catch (ClassNotFoundException e) {
				throw new RuntimeException(e);
			}
			System.out.println("---- results : " + results.size() + " ----");
			this.emit(results);
		}else{
			this.initializeDeletion(tables);
			partitioningDeletion(tables);

			List<InclusionDependency> results;
			try {
				results = this.AINDDiscoveryDeletion();
			} catch (SQLException e) {
				throw new RuntimeException(e);
			} catch (ClassNotFoundException e) {
				throw new RuntimeException(e);
			}
			System.out.println("result set size :" + results.size());
			this.emit(results);
		}
	}

	private void initializeCommon(List<TableInfo> tables) throws InputGenerationException, InputIterationException, AlgorithmConfigurationException{
		this.tempFolder = new File(this.tempFolderPath + File.separator + "temp");
		this.diskBack = new DiskBack(this.tempFolder);
		FileUtils.cleanDirectory(this.tempFolder);
		this.tableColumnStartIndexes = new int[this.tableNames.length];
		this.availableMemory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
		this.maxMemoryUsage = (long)((float)this.availableMemory * ((float)this.maxMemoryUsagePercentageUp / 100.0F));
		this.maxMemoryUsageLow = (long)((float)this.availableMemory * ((float)this.maxMemoryUsagePercentageLow / 100.0F));
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
		for(table = 0; table < this.tableNames.length; ++table) {
			this.tableColumnStartIndexes[table] = this.columnNames.size();
			this.collectStatisticsFrom(tables.get(table).selectInputGenerator());
		}
		this.numColumns = this.columnNames.size();
		this.bucketInMemPerPart = new Integer[this.numPartitionsPerColumn];
		for (int i = 0; i < this.bucketInMemPerPart.length; i++) {
			this.bucketInMemPerPart[i] = 0;
		}

		this.lineNameToIndex=new HashMap<>();
		for (int i = 0; i < numColumns; i++) lineNameToIndex.put(columnNames.get(i), i);
		this.leftViolation=new boolean[numColumns];
		this.rightViolation=new boolean[numColumns];
		this.violation= new boolean[numColumns];
		this.isNotFullMem = new boolean[numColumns];
		this.violationMatrix=new int[numColumns][numColumns];
		for (int i = 0; i < numColumns; i++) violationMatrix[i][i] = -1;
		this.rowVioNum=new int[numColumns];
		for (int i = 0; i < numColumns; i++) rowVioNum[i] = 1;
		this.lineVioNum=new int[numColumns];
		for (int i = 0; i < numColumns; i++) lineVioNum[i] = 1;

	}


	private void initializeInsertion(List<TableInfo> tables) throws InputGenerationException, InputIterationException, AlgorithmConfigurationException {
		this.partitions_list = new ArrayList<>();
		this.filterInsert_list = new ArrayList<>();
		this.columnThreshold=new int[numColumns][2];
	}

	private void initializeDeletion(List<TableInfo> tables) throws InputGenerationException, InputIterationException, AlgorithmConfigurationException {
		this.partitions_delete = new ArrayList<>();
		this.filterDelete_list = new ArrayList<>();

		this.rowCountOfTable = new int[tableNames.length];
		this.tableThreshold = new int[tableNames.length];
	}


	private void collectStatisticsFrom(RelationalInputGenerator inputGenerator) throws InputIterationException, InputGenerationException, AlgorithmConfigurationException {
		RelationalInput input = null;
		try {
			input = inputGenerator.generateNewCopy();
			Iterator var3 = input.columnNames().iterator();
			while(var3.hasNext()) {
				String columnName = (String)var3.next();
				this.columnNames.add(columnName);
			}
		} finally {
			FileUtils.close(input);
		}
	}

	protected boolean partitioningInsertion(List<TableInfo> tables) throws InputIterationException, InputGenerationException, AlgorithmConfigurationException {
		for(int tableIndex = 0; tableIndex < this.tableNames.length; ++tableIndex){
			int numTableColumns = this.tableColumnStartIndexes.length > tableIndex + 1 ? this.tableColumnStartIndexes[tableIndex + 1] - this.tableColumnStartIndexes[tableIndex] : this.numColumns - this.tableColumnStartIndexes[tableIndex];
			TableInfo table = tables.get(tableIndex);
			RelationalInputGenerator generator = table.selectInputGenerator();
			RelationalInput input = generator.generateNewCopy();
			int startTableColumnIndex = this.tableColumnStartIndexes[tableIndex];
			for (int i = 0; i < numPartitionsPerColumn; i++) {
				this.bucketInMemPerPart[i] += numTableColumns;
			}
			//partitions init, filters init
			for(int i = 0; i < numTableColumns; ++i) {
				List<Set<String>> attributePartitions = new ArrayList();
				List<Boolean> attributePartitionBacks = new ArrayList<>();
				List<ThreeLayersFilter> partitionBlooms = new ArrayList<>();
				List<int []> bucketSizeRanges = new ArrayList<>();
				for(int ptnIndex = 0; ptnIndex < this.numPartitionsPerColumn; ++ptnIndex) {
					attributePartitions.add(new HashSet());
					attributePartitionBacks.add(false);
					partitionBlooms.add(new ThreeLayersFilter(-1));
					bucketSizeRanges.add(new int [] {0,0});
				}
				this.partitions_list.add(attributePartitions);
				this.partition_backed.add(attributePartitionBacks);
				this.filterInsert_list.add(partitionBlooms);
				this.bucketSizeRange.add(bucketSizeRanges);
			}

			//read data files one by one
			int numValuesSinceLastMemoryCheck = 0;
			List<String> values;
			while(input.hasNext()) {
				values = input.next();
				int pNumber;
				for(int columnNumber = 0; columnNumber < numTableColumns; ++columnNumber) {
					String value = (String)values.get(columnNumber);
					if (value == null) continue;
					pNumber = this.calculatePartitionFor(value);
					if (((Set)((List)partitions_list.get(startTableColumnIndex + columnNumber)).get(pNumber)).add(value)) {
						++numValuesSinceLastMemoryCheck;
					}
					if (numValuesSinceLastMemoryCheck >= this.memoryCheckFrequency) {
						numValuesSinceLastMemoryCheck = 0;
						if (ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() > this.maxMemoryUsage){
							int count=0;
							while(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() > this.maxMemoryUsageLow) {
								findMaxBucketWriteBack(numTableColumns,startTableColumnIndex);
								count++;
								if(count % 10 == 0) System.gc();
							}

						}
						maxPartitionHeapCurr.clear();
					}
				}
			}
			//add to buckect queue ; update bucket size range
			for (int columnNumber = 0; columnNumber < numTableColumns; ++columnNumber){
				for (int partNumber = 0; partNumber < numPartitionsPerColumn; ++partNumber){
					int size = partitions_list.get(startTableColumnIndex + columnNumber).get(partNumber).size();
					PartitionSize partitionSize = new PartitionSize(startTableColumnIndex + columnNumber, partNumber,size);
					maxPartitionHeap.add(partitionSize);
					updateBucketSizeRange(startTableColumnIndex + columnNumber, partNumber, size);
				}
			}
		}
		return true;
	}

	protected boolean partitioningDeletion(List<TableInfo> tables) throws InputIterationException, InputGenerationException, AlgorithmConfigurationException {
		for(int tableIndex = 0; tableIndex < this.tableNames.length; ++tableIndex){
			int numTableColumns = this.tableColumnStartIndexes.length > tableIndex + 1 ? this.tableColumnStartIndexes[tableIndex + 1] - this.tableColumnStartIndexes[tableIndex] : this.numColumns - this.tableColumnStartIndexes[tableIndex];
			TableInfo table = tables.get(tableIndex);
			RelationalInputGenerator generator = table.selectInputGenerator();
			RelationalInput input = generator.generateNewCopy();

			int startTableColumnIndex = this.tableColumnStartIndexes[tableIndex];
			for (int i = 0; i < numPartitionsPerColumn; i++) {
				this.bucketInMemPerPart[i] += numTableColumns;
			}

			for(int i = 0; i < numTableColumns; ++i) {
				List<HashMap<String, Integer>> attributePartitions = new ArrayList();
				List<Boolean> attributePartitionBacks = new ArrayList<>();
				List<ThreeLayersFilterDelete> partitionBlooms = new ArrayList<>();
				List<int []> bucketSizeRanges = new ArrayList<>();
				for(int ptnIndex = 0; ptnIndex < this.numPartitionsPerColumn; ++ptnIndex) {
					attributePartitions.add(new HashMap<>());
					attributePartitionBacks.add(false);
					partitionBlooms.add(new ThreeLayersFilterDelete(-1)); //初始化
					bucketSizeRanges.add(new int [] {0,0});
				}
				this.partitions_delete.add(attributePartitions);
				this.partition_backed.add(attributePartitionBacks);
				this.filterDelete_list.add(partitionBlooms);
			}

			//read data files one by one

			int numValuesSinceLastMemoryCheck = 0;
			int rowCount = 0;
			while(input.hasNext()) {
				rowCount++;
				List<String> values = input.next();
				for(int columnNumber = 0; columnNumber < numTableColumns; ++columnNumber) {
					String value = (String)values.get(columnNumber);
					if(value==null){
						continue;
					}
					int pNumber = this.calculatePartitionFor(value);
					HashMap<String, Integer> map = partitions_delete.get(startTableColumnIndex + columnNumber).get(pNumber);
					if (map.containsKey(value)){
						map.put(value, map.get(value) + 1);
					}
					else{
						map.put(value, 1);
						++numValuesSinceLastMemoryCheck;
					}
					if (numValuesSinceLastMemoryCheck >= this.memoryCheckFrequency) {
						numValuesSinceLastMemoryCheck = 0;
						if (ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() > this.maxMemoryUsage){
							int count=0;
							while(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() > this.maxMemoryUsageLow) {
								findMaxBucketWriteBackDeletion(numTableColumns,startTableColumnIndex);
								count++;
								if(count % 10 == 0) System.gc();
							}
						}
						maxPartitionHeapCurr.clear();
					}
				}
			}
			this.rowCountOfTable[tableIndex] = rowCount;
			for (int columnNumber = 0; columnNumber < numTableColumns; ++columnNumber){
				for (int partNumber = 0; partNumber < numPartitionsPerColumn; ++partNumber){
					int size = this.partitions_delete.get(startTableColumnIndex + columnNumber).get(partNumber).size();
					PartitionSize partitionSize = new PartitionSize(startTableColumnIndex + columnNumber, partNumber,size);
					maxPartitionHeap.add(partitionSize);
				}
			}
		}
		return true;
	}

	protected void findMaxBucketWriteBack(int numTableColumns,int startTableColumnIndex){
		System.out.println("find max bucket and write back ");
		if (maxPartitionHeap.isEmpty()) {
			if (maxPartitionHeapCurr.isEmpty()) {
				for (int k = 0; k < numTableColumns; ++k){
					for (int partNumber = 0; partNumber < numPartitionsPerColumn; ++partNumber){
						int size = partitions_list.get(startTableColumnIndex + k).get(partNumber).size();
						PartitionSize partitionSize = new PartitionSize(startTableColumnIndex + k, partNumber,size);
						maxPartitionHeapCurr.add(partitionSize);
					}
				}
			}else{
				PartitionSize p = maxPartitionHeapCurr.poll();
				if (p != null){
					int colIndex = p.getAttributeID();
					int partIndex = p.getPartitionID();
					String colName = columnNames.get(colIndex);
					HashSet set = (HashSet) ((List) partitions_list.get(colIndex)).get(partIndex);
					updateBucketSizeRange(colIndex, partIndex, set.size());
					diskBack.writePartition(colName, partIndex, set);
					this.bucketInMemPerPart[partIndex] -= 1;
					partitions_list.get(colIndex).set(partIndex, new HashSet<>());
					this.partition_backed.get(colIndex).set(partIndex,true);
					this.isNotFullMem[colIndex] = true;
				}
			}
		}
		else {
			PartitionSize p = maxPartitionHeap.poll();
			if (p != null){
				int colIndex = p.getAttributeID();
				int partIndex = p.getPartitionID();
				String colName = columnNames.get(colIndex);
				System.out.println("write bucket to disk, column index  " + colIndex + " partition index :" + partIndex + "column name :"+colName);
				diskBack.writePartition(colName,partIndex,(HashSet) ((List) partitions_list.get(colIndex)).get(partIndex));
				this.bucketInMemPerPart[partIndex] -= 1;
				partitions_list.get(colIndex).set(partIndex, new HashSet<>());
				this.partition_backed.get(colIndex).set(partIndex,true);
				this.isNotFullMem[colIndex] = true;
			}
		}
	}

	protected void findMaxBucketWriteBackDeletion(int numTableColumns,int startTableColumnIndex){
		System.out.println("find max bucket and write back ");
		if (maxPartitionHeap.isEmpty()) {
			if (maxPartitionHeapCurr.isEmpty()) {
				for (int k = 0; k < numTableColumns; ++k){
					for (int partNumber = 0; partNumber < numPartitionsPerColumn; ++partNumber){
						int size = this.partitions_delete.get(startTableColumnIndex + k).get(partNumber).size();
						PartitionSize partitionSize = new PartitionSize(startTableColumnIndex + k, partNumber,size);
						maxPartitionHeapCurr.add(partitionSize);
					}
				}
			}else{
				PartitionSize p = maxPartitionHeapCurr.poll();
				if (p != null){
					int colIndex = p.getAttributeID();
					int partIndex = p.getPartitionID();
					String colName = columnNames.get(colIndex);
					HashMap<String, Integer> map = partitions_delete.get(colIndex).get(partIndex);
					diskBack.writePartition(colName, partIndex, map);
					this.bucketInMemPerPart[partIndex] -= 1;
					((List) partitions_delete.get(colIndex)).set(partIndex, new HashMap<>());
					this.partition_backed.get(colIndex).set(partIndex,true);
					this.isNotFullMem[colIndex] = true;
					System.gc();
				}
			}
		}
		else {
			PartitionSize p = maxPartitionHeap.poll();
			if (p != null){
				int colIndex = p.getAttributeID();
				int partIndex = p.getPartitionID();
				String colName = columnNames.get(colIndex);
				System.out.println("write bucket to disk, column index  " + colIndex + " partition index :" + partIndex + "column name :"+colName);
				diskBack.writePartition(colName,partIndex, partitions_delete.get(colIndex).get(partIndex));
				this.bucketInMemPerPart[partIndex] -= 1;
				partitions_delete.get(colIndex).set(partIndex, new HashMap<>());
				this.partition_backed.get(colIndex).set(partIndex,true);
				this.isNotFullMem[colIndex] = true;
				System.gc();
			}
		}
	}

	private void updateBucketSizeRange(int colIndex, int partIndex, int size){
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

	public void buildFilterDeletion(int filterSize, String colName, int colIndex, int partIndex, HashMap<String, Integer> map) {
		ThreeLayersFilterDelete<String> threeLayersFilterDelete = new ThreeLayersFilterDelete<>(filterSize,colName,colIndex,partIndex);
		for (String s : map.keySet()) {
			threeLayersFilterDelete.addBloomSet(s,map.get(s));
		}
		filterDelete_list.get(colIndex).set(partIndex, threeLayersFilterDelete);
	}

	protected List<InclusionDependency> AINDDiscovery() throws SQLException, ClassNotFoundException {
		maxPartitionHeapCurr.clear();
		computeVioLowUpBound();
		Integer[] partitionIdxsSortedBySize = getPartitionIdxsSortedBySize(this.bucketInMemPerPart);
		List<InclusionDependency> results = new ArrayList<>();
		int partitionCount = 0;
		for(int partitionNum : partitionIdxsSortedBySize){
			for (int colNum = 0; colNum < numColumns; colNum++) {
				if(filterInsert_list.get(colNum).get(partitionNum).getColIndex() != -1) continue;
				Set<String> bucketInMem = partitions_list.get(colNum).get(partitionNum);
				Set<String> bucketOutMem = null;
				if (partition_backed.get(colNum).get(partitionNum)){
					bucketOutMem = diskBack.readPartition(columnNames.get(colNum),partitionNum);
					bucketInMem.addAll(bucketOutMem);
				}
				int bucketSize = bucketInMem.size();
				updateVioLowUpBound(colNum, partitionNum, bucketSize);
			}
			for (int colNum = 0; colNum < numColumns; colNum++) {
				Set<String> bucketInMem = partitions_list.get(colNum).get(partitionNum);
				buildFilter(this.filterSize, this.columnNames.get(colNum),colNum, partitionNum, bucketInMem);
			}
			for (int colNum = 0; colNum < numColumns; colNum++) {
				partitions_list.get(colNum).set(partitionNum, new HashSet<>());
			}

			for (int i = 0; i < numColumns; i++){
				if (leftViolation[i]) continue;
				ThreeLayersFilter l = filterInsert_list.get(i).get(partitionNum);
				for (int j = 0; j < numColumns ; j++){
					if (j == i) continue;
					if(violationMatrix[i][j] == -1 ) continue;
					ThreeLayersFilter r = filterInsert_list.get(j).get(partitionNum);
					List<List<Integer>> left = l.getValues();
					List<List<Integer>> right = r.getValues();
					int count = 0;
					BitSet a = l.getBloomSet();
					for (int k = a.nextSetBit(0); k >= 0; k = a.nextSetBit(k+1)) {
						if (k == Integer.MAX_VALUE) {
							break;
						}
						if((l.getBloomNum()[k] - r.getBloomNum()[k]) > 0) {
							count += l.getBloomNum()[k] - r.getBloomNum()[k];
							if(count > columnThreshold[i][1]) break;
						}
					}
					if (count > columnThreshold[i][1]) {
						violationMatrix[i][j] = -1;
						addLeftOrRightVio(i, j);
						continue;
					}
					//exact calculation
					if(violationMatrix[i][j] != -1){
						updateMatrixFromFilterInsertion(i, j, partitionNum, left, right);
					}
				}
			}
			for (int colNum = 0; colNum < numColumns; colNum++) {
				filterInsert_list.get(colNum).set(partitionNum, null);
			}
			partitionCount++;
			if(partitionCount >10 &&  ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() > this.maxMemoryUsage){
				partitionCount=0;
			}
		}
		for (int i = 0; i < numColumns; i++) {
			for (int j = 0; j < numColumns; j++) {
				if (violationMatrix[i][j] == -1 || violationMatrix[i][j] > columnThreshold[i][1]) continue;
				ColumnIdentifier l = new ColumnIdentifier(tableNames[findTableIndex(i)], columnNames.get(i));
				ColumnPermutation lhs = new ColumnPermutation(l);
				ColumnIdentifier r = new ColumnIdentifier(tableNames[findTableIndex(j)], columnNames.get(j));
				ColumnPermutation rhs = new ColumnPermutation(r);
				InclusionDependency ind = new InclusionDependency(lhs, rhs);
				results.add(ind);
			}
		}
		return results;
	}

	protected List<InclusionDependency> AINDDiscoveryDeletion() throws SQLException, ClassNotFoundException {
		int tableIndex = this.tableNames.length - 1;
		int numTableColumns = this.numColumns - this.tableColumnStartIndexes[tableIndex];
		int startTableColumnIndex = this.tableColumnStartIndexes[tableIndex];
		while(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() > this.maxMemoryUsage) {
			findMaxBucketWriteBack(numTableColumns,startTableColumnIndex);
		}
		maxPartitionHeapCurr.clear();

		computeVioBound();
		Integer[] partitionIdxsSortedBySize = getPartitionIdxsSortedBySize(this.bucketInMemPerPart);
		List<InclusionDependency> results = new ArrayList<>();
		int partitionCount = 0;
		for(int partitionNum : partitionIdxsSortedBySize){
			for (int colNum = 0; colNum < numColumns; colNum++) {
				if(filterDelete_list.get(colNum).get(partitionNum).getColIndex() != -1) continue;
				HashMap<String, Integer> bucketInMem = partitions_delete.get(colNum).get(partitionNum);
				HashMap<String, Integer> bucketOutMem = null;
				if (partition_backed.get(colNum).get(partitionNum)){
					bucketOutMem = diskBack.readPartitionMap(columnNames.get(colNum),partitionNum);
					for (String s : bucketOutMem.keySet()) {
						if (bucketInMem.containsKey(s)) {
							bucketInMem.put(s, bucketInMem.get(s) + bucketOutMem.get(s));
						}else{
							bucketInMem.put(s, bucketOutMem.get(s));
						}
					}
				}
			}
			for (int colNum = 0; colNum < numColumns; colNum++) {
				HashMap<String, Integer> bucketInMem = partitions_delete.get(colNum).get(partitionNum);
				buildFilterDeletion(this.filterSize, this.columnNames.get(colNum),colNum, partitionNum, bucketInMem);
			}

			// free bucket
			for (int colNum = 0; colNum < numColumns; colNum++) {
				partitions_delete.get(colNum).set(partitionNum, new HashMap<>());
			}

			for (int i = 0; i < numColumns; i++){
				if (leftViolation[i]) continue;
				ThreeLayersFilterDelete l = filterDelete_list.get(i).get(partitionNum);
				for (int j = 0; j < numColumns ; j++){
					if (j == i) continue;
					if(violationMatrix[i][j] == -1 ) continue;
					ThreeLayersFilterDelete r = filterDelete_list.get(j).get(partitionNum);
					List<HashMap<String, Integer>> left = l.getValues();
					List<HashMap<String, Integer>> right = r.getValues();
					int count = 0;
					BitSet a = l.getBloomSet();
					for (int k = a.nextSetBit(0); k >= 0; k = a.nextSetBit(k+1)) {
						if (k == Integer.MAX_VALUE) {
							break;
						}
						if((l.getBloomNum()[k] - r.getBloomNum()[k]) > 0) {
							count += (l.getBloomNum()[k] - r.getBloomNum()[k])*l.getBloomMinCount()[k];
							if(count > tableThreshold[findTableIndex(i)]) break;
						}
					}
					if (count > tableThreshold[findTableIndex(i)]) {
						violationMatrix[i][j] = -1;
						addLeftOrRightVio(i, j);
						continue;
					}
					if(violationMatrix[i][j] != -1){
						updateMatrixFromFilterDeletion(i, j, partitionNum, left, right);
					}
				}
			}
			for (int colNum = 0; colNum < numColumns; colNum++) {
				filterDelete_list.get(colNum).set(partitionNum, null);
			}
			partitionCount++;
			if(partitionCount >10 &&  ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() > this.maxMemoryUsage){
				System.gc();
				partitionCount=0;
			}
		}

		for (int i = 0; i < numColumns; i++) {
			for (int j = 0; j < numColumns; j++) {
				if (violationMatrix[i][j] == -1 || violationMatrix[i][j] > tableThreshold[findTableIndex(i)]) continue;
				ColumnIdentifier l = new ColumnIdentifier(tableNames[findTableIndex(i)], columnNames.get(i));
				ColumnPermutation lhs = new ColumnPermutation(l);
				ColumnIdentifier r = new ColumnIdentifier(tableNames[findTableIndex(j)], columnNames.get(j));
				ColumnPermutation rhs = new ColumnPermutation(r);
				InclusionDependency ind = new InclusionDependency(lhs, rhs);
				results.add(ind);
			}
		}
		return results;
	}

	private void computeVioBound(){
		// calculate violation threshold for each table
		for (int i = 0; i < tableNames.length; i++) {
			this.tableThreshold[i] = (int)(this.rowCountOfTable[i] * (violate_per / 10000.0F));
		}
	}
	private int findTableIndex(int colNum) {
		// for a given column index, return table index that it belongs to
		for (int i = 0; i < tableColumnStartIndexes.length - 1; i++) {
			if (colNum >= tableColumnStartIndexes[i] && colNum < tableColumnStartIndexes[i + 1]) return i;
		}
		return tableColumnStartIndexes.length - 1;
	}

	private void computeVioLowUpBound(){
		// compute allowed violation number of each column
		for(int colIndex = 0; colIndex < numColumns; colIndex++) {
			int low = 0,up = 0;
			for (int partIndex = 0; partIndex < this.numPartitionsPerColumn; partIndex++) {
				low += bucketSizeRange.get(colIndex).get(partIndex)[0];
				up += bucketSizeRange.get(colIndex).get(partIndex)[1];
			}
			columnThreshold[colIndex][0] = (int)(low * (violate_per / 10000.0F));
			columnThreshold[colIndex][1] = (int)(up * (violate_per / 10000.0F));
		}
		return;
	}
	private void updateVioLowUpBound(int colNum, int partNum, int bucketSize){
		// update range by new bucket size
		if (bucketSizeRange.get(colNum).get(partNum)[0] == bucketSizeRange.get(colNum).get(partNum)[1] || columnThreshold[colNum][0] == columnThreshold[colNum][1]) {
			return;
		}
		bucketSizeRange.get(colNum).get(partNum)[0] = bucketSize;
		bucketSizeRange.get(colNum).get(partNum)[1] = bucketSize;
		int low = 0,up = 0;
		for (int partIndex = 0; partIndex < this.numPartitionsPerColumn; partIndex++) {
			low += bucketSizeRange.get(colNum).get(partIndex)[0];
			up += bucketSizeRange.get(colNum).get(partIndex)[1];
		}
		columnThreshold[colNum][0] = (int)(low * (violate_per / 10000.0F));
		columnThreshold[colNum][1] = (int)(up * (violate_per / 10000.0F));
		return;
	}


	private void updateMatrixFromFilterInsertion(int leftCol, int rightCol, int partID, List<List<Integer>> left, List<List<Integer>> right){
		int count = 0;
		for (int index = 0; index < left.size(); index++) {
			if (right.get(index).containsAll(left.get(index))) continue;
			else {
				if (this.violationMatrix[leftCol][rightCol] + 1 > columnThreshold[leftCol][1]) {
					violationMatrix[leftCol][rightCol] = -1;
					addLeftOrRightVio(leftCol, rightCol);
					break;
				}
			}

			List<Integer> tempList=new ArrayList<>(left.get(index));
			tempList.retainAll(right.get(index));
			count += left.get(index).size() - tempList.size();
			this.violationMatrix[leftCol][rightCol] += left.get(index).size() - tempList.size();
			if (this.violationMatrix[leftCol][rightCol] > columnThreshold[leftCol][1]) {
				violationMatrix[leftCol][rightCol] = -1;
				addLeftOrRightVio(leftCol, rightCol);
				break;
			}
		}
	}

	private void updateMatrixFromFilterDeletion(int leftCol, int rightCol, int partID, List<HashMap<String, Integer>> left, List<HashMap<String, Integer>> right){
		for (int index = 0; index < left.size(); index++) {
			HashMap<String,Integer> tempList = left.get(index);
			for (String s : tempList.keySet()) {
				if (!right.get(index).keySet().contains(s)){
					this.violationMatrix[leftCol][rightCol] +=left.get(index).get(s);
				}
			}
			if (this.violationMatrix[leftCol][rightCol] > tableThreshold[findTableIndex(leftCol)]) {
				violationMatrix[leftCol][rightCol] = -1;
				addLeftOrRightVio(leftCol, rightCol);
				break;
			}
		}
	}

	private void addLeftOrRightVio(int i,int j){
		rowVioNum[i]++;
		if(rowVioNum[i] >= numColumns) {
			leftViolation[i] = true;
			if(rightViolation[i]) violation[i] = true;
		}
		lineVioNum[j]++;
		if(lineVioNum[j] >= numColumns) {
			rightViolation[j] = true;
			if (leftViolation[j]) violation[j] = true;
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

	protected void emit(List<InclusionDependency> results) throws CouldNotReceiveResultException, ColumnNameMismatchException {
		for (InclusionDependency ind : results)
			this.resultReceiver.receiveResult(ind);
	}

	@Override
	public String toString() {
		return this.getClass().getName();
	}

	protected String getAuthorName() {
		return "SU and WANG";
	}

	protected String getDescriptionText() {
		return "AIND discovery";
	}
}
