# Introduction

AINDD is a solution to Approximate Inclusion Dependency discovery. Given several tables, AINDD efficiently find all AINDs for specific violation threshold.



# **Usage**

The [Metanome](https://hpi.de/naumann/projects/data-profiling-and-analytics/metanome-data-profiling.html) profiling tool is a framework for various profiling algorithms. It handles both algorithms and datasets as external resources. To compare with other IND discovery algorithms on the Metanome platform, we implemented code adapted to the Metanome platform. 


To run our algorithm, you need to :

- clone source code of AINDD
- ensure the following configuration
  - `Java 9 or later`
  - `Maven 3.1.0 or later`
- package AINDD using `mvn package` , or you can directly use the jar file provided in `target` package
- put `AINDD-1.0-SNAPSHOT.jar` under `Metanome/backend/WEB-INF/classes/algorithms`
- run AINDD on Metanome


When you run AINDD on the Metanome platform, you need to set:

- `MAX_MEMORY_USAGE_PERCENTAGE_UP` The upper bound of utilization required for memory management, which we recommend setting to 80 (in %), as we set in our experiments.
- `MAX_MEMORY_USAGE_PERCENTAGE_LOW` The low bound of utilization required for memory management, which we recommend setting to 60 (in %).
- `NUM_BUCKETS_PER_COLUMN` number of partitions.To facilitate the verification of the results of Exp-4, AINDD provides settings for the number of partitions and filter size that can be specified.
- `FILTER_SIZE` size of three-layer filter
- `VIOLATE_PER_10000` violation threshold (per 10,000)
- `DELETE_MODE` whether to use the delete semantics


All of the above parameters are set via the Metanome front-end page.


You can learn more about usage of Metanome in [here](https://hpi.de/naumann/projects/data-profiling-and-analytics/metanome-data-profiling/algorithms.html)



# **Comparative Experiments**
The comparison algorithms used in AINDD experiments are [A-DeMarchi](https://github.com/A-IND/AINDD-Expt/tree/main/ComparisonAlgorithms/A-DeMarchi),[A-SPIDER](https://github.com/A-IND/AINDD-Expt/tree/main/ComparisonAlgorithms/A-SPIDER) and [BINDER](https://github.com/A-IND/AINDD-Expt/tree/main/ComparisonAlgorithms/BINDER).

We provide the algorithm code and datasets required for the experiments in [here](https://github.com/A-IND/AINDD-Expt)




# License

AINDD is released under the [Apache 2.0 license](https://github.com/A-IND/AINDD/blob/main/LICENSE). Some basic data structure's source code is imported from [BINDER](https://github.com/HPI-Information-Systems/metanome-algorithms/tree/master/BINDER)


# Remarks

Due to time constraints, we will be updating the repository later and expect to finish by 2024.7.8

