package me.salamander.cctransformer.util;

@FunctionalInterface
public interface XYZPredicate {
    boolean test(int x, int y, int z);
}
