package de.metanome.algorithms.aindd.io;

import java.io.*;
import java.util.*;
import de.uni_potsdam.utils.FileUtils;

public class FilterDiskBack {

    private File tempFolder;

    public FilterDiskBack(File tempFolder) {
        this.tempFolder = tempFolder;
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String getFilePath(String colName, int partID) {
        String safeColName = sanitizeFileName(colName);
        return tempFolder.getPath()
                + File.separator
                + safeColName +"_"+partID;
    }

    public void writeThirdLayer(String colName,
                            int partID,
                            List<List<String>> values) {

    String path = getFilePath(colName, partID);
    BufferedWriter writer = null;

    try {
        writer = FileUtils.buildFileWriter(path, false);

        for (int pos = 0; pos < values.size(); pos++) {
            List<String> vals = values.get(pos);
            if (vals == null || vals.isEmpty()) continue;

            // position
            writer.write(Integer.toString(pos));
            writer.write("\t");

            // value-set: v1,v2,v3
            for (int i = 0; i < vals.size(); i++) {
                if (i > 0) writer.write(";");
                writer.write(vals.get(i));
            }
            writer.newLine();
        }
    } catch (IOException e) {
        throw new RuntimeException(e);
    } finally {
        FileUtils.close(writer);
    }
}



   public List<List<String>> readThirdLayer(String colName,
                                         int partID,
                                         int filterSize) {

    List<List<String>> values = new ArrayList<>();
    for (int i = 0; i < filterSize; i++) {
        values.add(new ArrayList<>());
    }

    String path = getFilePath(colName, partID);
    File file = new File(path);
    if (!file.exists()) return values;

    BufferedReader reader = null;
    try {
        reader = FileUtils.buildFileReader(path);
        String line;

        while ((line = reader.readLine()) != null) {
            String[] parts = line.split("\t");
            int pos = Integer.parseInt(parts[0]);

            if (parts.length > 1) {
                String[] vals = parts[1].split(";");
                for (String v : vals) {
                    values.get(pos).add(v);
                }
            }
        }
    } catch (IOException e) {
        throw new RuntimeException(e);
    } finally {
        FileUtils.close(reader);
    }

    return values;
}
}