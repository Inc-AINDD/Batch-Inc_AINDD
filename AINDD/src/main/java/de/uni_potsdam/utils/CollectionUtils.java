package de.uni_potsdam.utils;

/**
 * @projectName: AproINDAlgo
 * @package: de.uni_potsdam.utils
 * @className: CollectionUtils
 * @author: SuQingdong
 * @description: TODO
 * @date: 2023/12/23 14:45
 * @version: 1.0
 */

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntListIterator;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongListIterator;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class CollectionUtils {
    public CollectionUtils() {
    }

    public static <T> int countNull(Collection<T> objects) {
        int count = 0;
        Iterator var2 = objects.iterator();

        while(var2.hasNext()) {
            Object o = var2.next();
            if (o == null) {
                ++count;
            }
        }

        return count;
    }

    public static <T> int countNotNull(Collection<T> objects) {
        return objects.size() - countNull(objects);
    }

    public static int countN(int[] numbers, int numberToCount) {
        int count = 0;
        int[] var3 = numbers;
        int var4 = numbers.length;

        for(int var5 = 0; var5 < var4; ++var5) {
            int number = var3[var5];
            if (number == numberToCount) {
                ++count;
            }
        }

        return count;
    }

    public static int countNotN(int[] numbers, int numberNotToCount) {
        return numbers.length - countN(numbers, numberNotToCount);
    }

    public static int countN(LongArrayList numbers, int numberToCount) {
        int count = 0;
        LongListIterator var3 = numbers.iterator();

        while(var3.hasNext()) {
            long number = (Long)var3.next();
            if (number == (long)numberToCount) {
                ++count;
            }
        }

        return count;
    }

    public static int countNotN(LongArrayList numbers, int numberNotToCount) {
        return numbers.size() - countN(numbers, numberNotToCount);
    }

    public static String concat(Iterable<String> strings, String separator) {
        if (strings == null) {
            return "";
        } else {
            StringBuilder buffer = new StringBuilder();
            Iterator var3 = strings.iterator();

            while(var3.hasNext()) {
                String string = (String)var3.next();
                buffer.append(string);
                buffer.append(separator);
            }

            if (buffer.length() > separator.length()) {
                buffer.delete(buffer.length() - separator.length(), buffer.length());
            }

            return buffer.toString();
        }
    }

    public static String concat(IntArrayList integers, String separator) {
        if (integers == null) {
            return "";
        } else {
            StringBuilder buffer = new StringBuilder();
            IntListIterator var3 = integers.iterator();

            while(var3.hasNext()) {
                int integer = (Integer)var3.next();
                buffer.append(integer);
                buffer.append(separator);
            }

            if (buffer.length() > separator.length()) {
                buffer.delete(buffer.length() - separator.length(), buffer.length());
            }

            return buffer.toString();
        }
    }

    public static String concat(LongArrayList longs, String separator) {
        if (longs == null) {
            return "";
        } else {
            StringBuilder buffer = new StringBuilder();
            LongListIterator var3 = longs.iterator();

            while(var3.hasNext()) {
                long longValue = (Long)var3.next();
                buffer.append(longValue);
                buffer.append(separator);
            }

            if (buffer.length() > separator.length()) {
                buffer.delete(buffer.length() - separator.length(), buffer.length());
            }

            return buffer.toString();
        }
    }

    public static String concat(Object[] objects, String separator) {
        if (objects == null) {
            return "";
        } else {
            StringBuilder buffer = new StringBuilder();

            for(int i = 0; i < objects.length; ++i) {
                buffer.append(objects[i].toString());
                if (i + 1 < objects.length) {
                    buffer.append(separator);
                }
            }

            return buffer.toString();
        }
    }

    public static String concat(int[] numbers, String separator) {
        if (numbers == null) {
            return "";
        } else {
            StringBuilder buffer = new StringBuilder();

            for(int i = 0; i < numbers.length; ++i) {
                buffer.append(numbers[i]);
                if (i + 1 < numbers.length) {
                    buffer.append(separator);
                }
            }

            return buffer.toString();
        }
    }

    public static String concat(boolean[] booleans, String separator) {
        if (booleans == null) {
            return "";
        } else {
            StringBuilder buffer = new StringBuilder();

            for(int i = 0; i < booleans.length; ++i) {
                buffer.append(booleans[i]);
                if (i + 1 < booleans.length) {
                    buffer.append(separator);
                }
            }

            return buffer.toString();
        }
    }

    public static String concat(List<int[]> numbersList, String innerSeparator, String outerSeparator) {
        if (numbersList == null) {
            return "";
        } else {
            StringBuilder buffer = new StringBuilder();
            Iterator<int[]> iterator = numbersList.iterator();

            while(iterator.hasNext()) {
                int[] numbers = (int[])iterator.next();

                for(int i = 0; i < numbers.length; ++i) {
                    buffer.append(numbers[i]);
                    if (i + 1 < numbers.length) {
                        buffer.append(innerSeparator);
                    }
                }

                if (iterator.hasNext()) {
                    buffer.append(outerSeparator);
                }
            }

            return buffer.toString();
        }
    }

    public static String concat(String[] strings, String prefix, String suffix, String separator) {
        if (strings == null) {
            return "";
        } else {
            StringBuilder buffer = new StringBuilder();

            for(int i = 0; i < strings.length; ++i) {
                buffer.append(prefix + strings[i] + suffix);
                if (i + 1 < strings.length) {
                    buffer.append(separator);
                }
            }

            return buffer.toString();
        }
    }

    public static String concat(int times, String string, String separator) {
        StringBuilder buffer = new StringBuilder();

        for(int i = 0; i < times; ++i) {
            buffer.append(string);
            if (i + 1 < times) {
                buffer.append(separator);
            }
        }

        return buffer.toString();
    }

    public static String concat(String[] stringsA, String[] stringsB, String separatorStrings, String separatorPairs) {
        if (stringsA != null && stringsB != null) {
            StringBuilder buffer = new StringBuilder();
            int times = Math.max(stringsA.length, stringsB.length);

            for(int i = 0; i < times; ++i) {
                if (stringsA.length > i) {
                    buffer.append(stringsA[i]);
                }

                if (stringsA.length > i && stringsB.length > i) {
                    buffer.append(separatorStrings);
                }

                if (stringsB.length > i) {
                    buffer.append(stringsB[i]);
                }

                if (i + 1 < times) {
                    buffer.append(separatorPairs);
                }
            }

            return buffer.toString();
        } else {
            return "";
        }
    }

    public static String concat(ObjectArrayList<int[][]> samples, String separator) {
        if (samples == null) {
            return "";
        } else {
            StringBuilder buffer = new StringBuilder();
            ObjectListIterator var3 = samples.iterator();

            while(var3.hasNext()) {
                int[][] sample = (int[][])var3.next();
                buffer.append(sample.length + separator);
            }

            return buffer.substring(0, buffer.length() - 1);
        }
    }

    public static boolean removeIntersectionFrom(Set<String> first, Set<String> second) {
        Set<String> intersection = new ObjectOpenHashSet(first);
        intersection.retainAll(second);
        first.removeAll(intersection);
        second.removeAll(intersection);
        return !intersection.isEmpty();
    }

    public static int max(int[] values) {
        if (values == null) {
            throw new RuntimeException("The maximum of null is not defined!");
        } else if (values.length == 0) {
            throw new RuntimeException("The maximum of an empty list is not defined!");
        } else {
            int max = values[0];

            for(int i = 1; i < values.length; ++i) {
                if (max < values[i]) {
                    max = values[i];
                }
            }

            return max;
        }
    }

    public static int min(int[] values) {
        if (values == null) {
            throw new RuntimeException("The minimum of null is not defined!");
        } else if (values.length == 0) {
            throw new RuntimeException("The minimum of an empty list is not defined!");
        } else {
            int min = values[0];

            for(int i = 1; i < values.length; ++i) {
                if (min > values[i]) {
                    min = values[i];
                }
            }

            return min;
        }
    }
}
