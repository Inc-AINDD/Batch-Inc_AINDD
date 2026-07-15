package de.metanome.algorithms.aindd.io;

/**
 * @projectName: AINDD
 * @package: de.metanome.algorithms.aindd.io
 * @className: FileInputIterator
 * @author: SuQingdong
 * @description: TODO
 * @date: 2023/12/23 15:23
 * @version: 1.0
 */

import de.metanome.algorithm_integration.AlgorithmConfigurationException;
import de.metanome.algorithm_integration.input.InputGenerationException;
import de.metanome.algorithm_integration.input.InputIterationException;
import de.metanome.algorithm_integration.input.RelationalInput;
import de.metanome.algorithm_integration.input.RelationalInputGenerator;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class FileInputIterator implements InputIterator {
    private RelationalInput inputGenerator = null;
    private List<String> record = null;
    private int rowsRead = 0;
    private int inputRowLimit;

    public FileInputIterator(RelationalInputGenerator inputGenerator, int inputRowLimit) throws InputGenerationException, AlgorithmConfigurationException {
        this.inputGenerator = inputGenerator.generateNewCopy();
        this.inputRowLimit = inputRowLimit;
    }

    public boolean next() throws InputIterationException {
        if (!this.inputGenerator.hasNext() || this.inputRowLimit > 0 && this.rowsRead >= this.inputRowLimit) {
            return false;
        } else {
            List<String> input = this.inputGenerator.next();
            this.record = new ArrayList(input.size());

            String value;
            for(Iterator var2 = input.iterator(); var2.hasNext(); this.record.add(value)) {
                value = (String)var2.next();
                if (value != null) {
                    value = value.replaceAll("\n", "\u0000");
                }
            }

            ++this.rowsRead;
            return true;
        }
    }

    public String getValue(int columnIndex) {
        return (String)this.record.get(columnIndex);
    }

    public List<String> getValues() {
        return this.record;
    }

    public void close() throws Exception {
        this.inputGenerator.close();
    }
}
