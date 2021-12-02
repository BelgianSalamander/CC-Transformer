package me.salamander.cctransformer.util;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public class ASMUtil {
    public static int argumentSize(String desc, boolean isStatic) {
        Type[] argTypes = Type.getArgumentTypes(desc);

        int size = 0;
        if(!isStatic) {
            size++;
        }

        for(Type type : argTypes) {
            size += type.getSize();
        }

        return size;
    }

    public static boolean isStatic(MethodNode methodNode) {
        return (methodNode.access & Opcodes.ACC_STATIC) != 0;
    }

    public static int argumentCount(String desc, boolean isStatic) {
        Type[] argTypes = Type.getArgumentTypes(desc);

        int size = argTypes.length;
        if(!isStatic) {
            size++;
        }

        return size;
    }

    public static <T> void varIndicesToArgIndices(T[] varArr, T[] argArr, String desc, boolean isStatic){
        Type[] argTypes = Type.getArgumentTypes(desc);
        int staticOffset = isStatic ? 0 : 1;
        if(argArr.length != argTypes.length + staticOffset){
            throw new IllegalArgumentException("argArr.length != argTypes.length");
        }

        int varIndex = 0;
        int argIndex = 0;

        if(!isStatic){
            argArr[0] = varArr[0];
            varIndex++;
            argIndex++;
        }

        for(Type type: argTypes){
            argArr[argIndex] = varArr[varIndex];
            varIndex += type.getSize();
            argIndex++;
        }
    }

    public static String onlyClassName(String name) {
        name = name.replace('/', '.');
        int index = name.lastIndexOf('.');
        if(index == -1) {
            return name;
        }
        return name.substring(index + 1);
    }
}
