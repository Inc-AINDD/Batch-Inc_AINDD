//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package de.metanome.util;

import java.util.BitSet;

public class BitSetIterator {
    private final BitSet set;
    private int position;

    BitSetIterator(BitSet set, int from) {
        this.set = set;
        this.position = set.nextSetBit(from);
    }

    public boolean hasNext() {
        return this.position >= 0;
    }

    public int next() {
        int current = this.position;
        this.position = this.set.nextSetBit(this.position + 1);
        return current;
    }

    public static BitSetIterator of(BitSet set) {
        return new BitSetIterator(set, 0);
    }
}
