package de.metanome.algorithms.aindd.io;

import de.uni_potsdam.utils.FileUtils;

import java.io.*;
import java.util.*;

/**
 * @projectName: AproINDAlgo
 * @package: de.metanome.algorithms.aindd.io
 * @className: DiskBack
 * @author: SuQingdong
 * @description: 磁盘读取 写回 操作类
 * @date: 2024/1/8 20:33
 * @version: 1.0
 */
public class DiskBack {
    private File tempFolder;

    public DiskBack(File tempFolder) {
        this.tempFolder = tempFolder;
    }


    public  void writePartition(String colName, int partNum, HashSet<String> values){
        String listFilePath = getFilePath(colName, partNum);
        writeToDisk(listFilePath, values);
    }
    public  Set<String> readPartition(String colName, int partNum){
        String path = getFilePath(colName, partNum);
        return readFromDisk(path);
    }

    public  void writePartition(String colName, int partNum, HashMap<String, Integer> map){
        System.out.println("write Partition");
        String listFilePath = getFilePath(colName, partNum);
        writeToDisk(listFilePath, map);
    }
    public HashMap<String, Integer> readPartitionMap(String colName, int partNum){
        String path = getFilePath(colName, partNum);
        return readFromDiskMap(path);
    }

    public  void writeToDisk(String bucketFilePath, HashSet<String> values){
        System.out.println("write to disk begin");
        System.out.println(values.size());
        if (values != null && !values.isEmpty()) {
            BufferedWriter writer = null;
            try {
                writer = FileUtils.buildFileWriter(bucketFilePath, true);
                Iterator<String> valueIterator = values.iterator();
                while(valueIterator.hasNext()) {
                    writer.write((String)valueIterator.next());
                    //System.out.println(valueIterator.next());
                    writer.newLine();
                }
                writer.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                FileUtils.close(writer);
            }
        }
        System.out.println("write to disk over");
    }

    public  void writeToDisk(String bucketFilePath, HashMap<String, Integer> map){
        System.out.println("write to disk begin");
        if (map != null && !map.isEmpty()) {
            BufferedWriter writer = null;
            try {
                writer = FileUtils.buildFileWriter(bucketFilePath, true);
                for (Map.Entry<String, Integer> entry : map.entrySet()){
                    writer.write(entry.getKey() + ":" + entry.getValue());
                    writer.newLine();
                }
                writer.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                FileUtils.close(writer);
            }
        }
        System.out.println("write to disk over");
    }


    public  Set<String> readFromDisk(String bucketFilePath){
        File file = new File(bucketFilePath);
        Set<String> values = new HashSet<>();
        if (file.exists()) {
            BufferedReader reader = null;
            String value = null;
            try {
                reader = FileUtils.buildFileReader(bucketFilePath);
                while((value = reader.readLine()) != null) {
                    values.add(value);
                }
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                FileUtils.close(reader);
            }
        }
        return values;
    }

    public  HashMap<String, Integer> readFromDiskMap(String bucketFilePath){
        File file = new File(bucketFilePath);
        HashMap<String, Integer> map = new HashMap<>();
        if (file.exists()) {
            BufferedReader reader = null;
            String line = null;
            try {
                reader = FileUtils.buildFileReader(bucketFilePath);
                while((line = reader.readLine()) != null) {
                    int lastIndex = line.lastIndexOf(':');
                    String key = line.substring(0,lastIndex);
                    int value = Integer.valueOf(line.substring(lastIndex + 1, line.length()));
                    if (map.containsKey(key)){
                        map.put(key, map.get(key) + value);
                    }
                    else{
                        map.put(key, value);
                    }
                }
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                FileUtils.close(reader);
            }
        }
        return map;
    }


    public  String getFilePath(String colName, int partID) {
        /**
         * @description: generate file path
         * @param colName:
         * @param partID:
         * @return java.lang.String
         * @author: SuQingdong
         * @date: 2024/1/8 20:15
         */
        return partID < 0 ? tempFolder.getPath() + File.separator + colName : tempFolder.getPath() + File.separator + colName + "_" + partID;
    }
}
