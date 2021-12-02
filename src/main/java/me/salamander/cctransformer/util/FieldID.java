package me.salamander.cctransformer.util;

import org.objectweb.asm.Type;

public record FieldID(Type owner, String name, Type desc) implements Ancestralizable<FieldID>{
    @Override
    public Type getAssociatedType() {
        return owner;
    }

    @Override
    public FieldID withType(Type type) {
        return new FieldID(type, name, desc);
    }

    @Override
    public boolean equalsWithoutType(FieldID other) {
        return other.name.equals(name) && other.desc.equals(desc);
    }

    @Override
    public String toString() {
        return ASMUtil.onlyClassName(owner.getClassName()) + "." + name;
    }
}
