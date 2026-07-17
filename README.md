# Introduction

AINDD is a solution to Approximate Inclusion Dependency discovery. Given several tables, AINDD efficiently finds all AINDs under a specific violation threshold. In this project, we provide a batch discovery method and an incremental update method for maintaining AINDs under dynamic data updates.



# Usage

The [Metanome](https://hpi.de/naumann/projects/data-profiling-and-analytics/metanome-data-profiling.html) profiling tool is a framework for various profiling algorithms. It handles both algorithms and datasets as external resources. To compare with other IND discovery algorithms on the Metanome platform, we implemented code adapted to the Metanome platform. 


To run our algorithm, you need to :

- clone source code of AINDD and IncAINDD
- ensure the following configuration
  - `Java 9 or later`
  - `Maven 3.1.0 or later`
- package AINDD and IncAINDD using `mvn package` , or you can directly use the jar file provided in `target` package
- put `AINDD-1.0-SNAPSHOT.jar` and `IncAINDD-1.0-SNAPSHOT.jar` under `Metanome/backend/WEB-INF/classes/algorithms`
- run AINDD or IncAINDD on Metanome


When you run AINDD on the Metanome platform, you need to set:

- `MAX_MEMORY_USAGE_PERCENTAGE_UP` The upper bound of utilization required for memory management, which we recommend setting to 80 (in %), as we set in our experiments.
- `MAX_MEMORY_USAGE_PERCENTAGE_LOW` The low bound of utilization required for memory management, which we recommend setting to 60 (in %).
- `NUM_BUCKETS_PER_COLUMN` number of partitions.To facilitate the verification of the results of Exp-4, AINDD provides settings for the number of partitions and filter size that can be specified.
- `FILTER_SIZE` size of three-layer filter
- `VIOLATE_PER_10000` violation threshold (per 10,000)
- `DELETE_MODE` whether to use the delete semantics

When running the incremental version, the following additional parameters need to be configured:

- `SAVE2DISK_BATCH`: The number of columns stored and processed in each batch during incremental updates, which we recommend setting to the number of columns in the dataset if sufficient memory is available.
- `MICRO_PROBING_THRESHOLD`: The threshold for fine pruning in incremental updates, which we recommend setting it to 1000.
- `DATA_INSERTION` whether data insertion occurs
- `DATA_DELETION` whether data deletion occurs

All of the above parameters are set via the Metanome front-end page.

To correctly run the incremental algorithm, you need to :
- select the original dataset, leave `DATA_INSERTION` and `DATA_DELETION` unchecked, and run the IncAINDD algorithm
- copy the context information and the auxiliary structure folder `AINDD_temp` to `AINDD_temp2` after the execution is completed
- input the datasets in the following order : the original dataset, deletion data (if any), and insertion data (if any). Enable the corresponding `DATA_INSERTION` (if any) and `DATA_DELETION` (if any) options, and run the IncAINDD algorithm
- The updated context information and auxiliary structures are saved in `AINDD_temp2`.
- If a comparison with the batch approach is required, use the incrementally updated data as input and run AINDD algorithm.

To facilitate the evaluation of the IncAINDD algorithm, we provide some example datasets [here](https://github.com/Inc-AINDD/IncAINDD-Exp).

You can learn more about usage of Metanome [here](https://hpi.de/naumann/projects/data-profiling-and-analytics/metanome-data-profiling/algorithms.html).



# Comparative Experiments
The comparison algorithms used in Batch-Inc_AINDD experiments are [A-DeMarchi](https://github.com/A-IND/AINDD-Expt/tree/main/ComparisonAlgorithms/A-DeMarchi),[A-SPIDER](https://github.com/A-IND/AINDD-Expt/tree/main/ComparisonAlgorithms/A-SPIDER) and [BINDER](https://github.com/A-IND/AINDD-Expt/tree/main/ComparisonAlgorithms/BINDER).

We provide the algorithm code and datasets required for the experiments [here](https://github.com/A-IND/AINDD-Expt).




# License

Batch-Inc_AINDD is released under the [Apache 2.0 license](https://github.com/Inc-AINDD/Batch-Inc_AINDD/blob/main/LICENSE). Some basic data structure's source code is imported from [BINDER](https://github.com/HPI-Information-Systems/metanome-algorithms/tree/master/BINDER).

