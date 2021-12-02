package me.salamander.cctransformer.bytecodegen;

import org.objectweb.asm.tree.InsnList;

public interface BytecodeFactory {
    InsnList generate();
}
