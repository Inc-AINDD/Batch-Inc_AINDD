package de.metanome.algorithms.aindd.structures;

/**
 * @projectName: AINDD
 * @package: de.metanome.algorithms.aindd.structures
 * @className: PartitionSize
 * @author: SuQingdong
 * @description:
 * @date: 2024/1/11 15:51
 * @version: 1.0
 */
public class PartitionSize {
    private int attributeID;
    private int partitionID;
    private int size;

    public PartitionSize(int attributeID, int partitionID, int size) {
        this.attributeID = attributeID;
        this.partitionID = partitionID;
        this.size = size;
    }

    public int getAttributeID() {
        return attributeID;
    }

    public void setAttributeID(int attributeID) {
        this.attributeID = attributeID;
    }

    public int getPartitionID() {
        return partitionID;
    }

    public void setPartitionID(int partitionID) {
        this.partitionID = partitionID;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }
}
