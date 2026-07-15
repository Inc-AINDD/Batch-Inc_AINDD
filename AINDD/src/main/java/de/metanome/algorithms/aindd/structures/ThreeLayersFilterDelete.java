package de.metanome.algorithms.aindd.structures;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;

public class ThreeLayersFilterDelete<T> {
    protected BitSet bloomSet;//唯一哈希函数映射
    protected List<HashMap<String,Integer>> values;
    protected int size;//规定bloomSet的大小
    protected int[] bloomNum;//对应bloomSet每一位出现的次数
    protected int[] bloomMinCount;// 删除语义专用 只存储最小出现频率
    protected String name;
    protected int colIndex;
    protected int partIndex;


    public ThreeLayersFilterDelete(int size, String name, int colIndex, int partIndex){
        this.size=size;
        this.colIndex = colIndex;
        this.partIndex = partIndex;
        this.bloomNum=new int[size];
        this.bloomMinCount=new int[size];
        this.bloomSet=new BitSet(size);
        this.values = new ArrayList<>();
        for (int i = 0; i < size; i++) this.values.add(new HashMap<>());
        this.name=name;
    }

    public ThreeLayersFilterDelete(int colIndex){
        //初始化时 用-1 来占位
        this.colIndex = colIndex;
    }

    //重构不同的数据类型
    public void addBloomSet(String num,Integer count){
        int h;
        int code=(h = num.hashCode()) ^ (h >>> 16);
        int temp=code & (size - 1);
        bloomSet.set(temp);
 //       this.values.get(temp).add(num.hashCode());
        this.values.get(temp).put(num,count);
        bloomNum[temp]++;
        bloomMinCount[temp]=Math.min(bloomNum[temp],count);
    }




//    public void addValues(Set<String> num){
//        for (String v : num){
//            int h;
//            int code=(h = v.hashCode()) ^ (h >>> 16);
//            int temp=code & (size - 1);
//            this.values.get(temp).add(code);
//        }
//    }

    public String getName() {
        return name;
    }

    public int getColIndex() {
        return colIndex;
    }


    public BitSet getBloomSet(){
        return bloomSet;
    }

    public int[] getBloomMinCount() {
        return bloomMinCount;
    }

    public int[] getBloomNum(){
        return bloomNum;
    }

    public List<HashMap<String, Integer>> getValues() {
        return values;
    }

    public int getSize() {
        return size;
    }

    public int getPartIndex() {
        return partIndex;
    }
}
