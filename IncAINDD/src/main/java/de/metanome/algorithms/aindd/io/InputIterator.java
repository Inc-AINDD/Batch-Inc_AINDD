package de.metanome.algorithms.aindd.io;

/**
 * @projectName: AproINDAlgo
 * @package: de.metanome.algorithms.aindd.io
 * @className: InputIterator
 * @author: SuQingdong
 * @description: TODO
 * @date: 2023/12/23 15:22
 * @version: 1.0
 */

import de.metanome.algorithm_integration.input.InputIterationException;
import java.util.List;

public interface InputIterator extends AutoCloseable {
    boolean next() throws InputIterationException;

    String getValue(int var1) throws InputIterationException;

    List<String> getValues() throws InputIterationException;
}
