package me.salamander.cctransformer.util;

import org.objectweb.asm.Type;

public interface Ancestralizable<T extends Ancestralizable<T>> {
    Type getAssociatedType();
    T withType(Type type);
    boolean equalsWithoutType(T other);
}
