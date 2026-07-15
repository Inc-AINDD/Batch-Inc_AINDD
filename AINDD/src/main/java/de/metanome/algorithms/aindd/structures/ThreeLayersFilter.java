package de.metanome.algorithms.aindd.structures;

import java.util.*;

public class ThreeLayersFilter<T> {
    protected BitSet bloomSet;
    protected List<List<Integer>> values;
    protected int size;
    protected int[] bloomNum;
    protected String name;
    protected int colIndex;
    protected int partIndex;


    public ThreeLayersFilter(int size, String name, int colIndex, int partIndex){
        this.size=size;
        this.colIndex = colIndex;
        this.partIndex = partIndex;
        this.bloomNum=new int[size];
        this.bloomSet=new BitSet(size);
        this.values = new ArrayList<>();
        for (int i = 0; i < size; i++) this.values.add(new ArrayList<>());
        this.name=name;
    }

    public ThreeLayersFilter(int colIndex){
        this.colIndex = colIndex;
    }

    public void addBloomSet(String num){
        int h;
        int code=(h = num.hashCode()) ^ (h >>> 16);
        int temp=code & (size - 1);
        bloomSet.set(temp);
        this.values.get(temp).add(num.hashCode());
        bloomNum[temp]++;
    }

    public String getName() {
        return name;
    }
    public int getColIndex() {
        return colIndex;
    }

    public BitSet getBloomSet(){
        return bloomSet;
    }
    public int[] getBloomNum(){
        return bloomNum;
    }
    public List<List<Integer>> getValues() {
        return values;
    }
}
