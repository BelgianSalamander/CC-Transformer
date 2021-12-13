package me.salamander.cctransformer.util;

import me.salamander.cctransformer.transformer.analysis.TransformTrackingValue;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Value;

import static org.objectweb.asm.Opcodes.*;

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
        return (methodNode.access & ACC_STATIC) != 0;
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

    public static <T extends Value> T getTop(Frame<T> frame) {
        return frame.getStack(frame.getStackSize() - 1);
    }

    public static Type getType(int opcode) {
        return switch (opcode) {
            case ALOAD, ASTORE -> Type.getType("Ljava/lang/Object;");
            case DLOAD, DSTORE -> Type.DOUBLE_TYPE;
            case FLOAD, FSTORE -> Type.FLOAT_TYPE;
            case ILOAD, ISTORE -> Type.INT_TYPE;
            case LLOAD, LSTORE -> Type.LONG_TYPE;
            default -> {throw new UnsupportedOperationException("Opcode " + opcode + " is not supported yet!");}
        };
    }

    public static int stackConsumed(AbstractInsnNode insn) {
        if(insn instanceof MethodInsnNode methodCall){
            return argumentCount(methodCall.desc, methodCall.getOpcode() == INVOKESTATIC);
        }else if(insn instanceof InvokeDynamicInsnNode dynamicCall){
            return argumentCount(dynamicCall.desc, true);
        }else{
            return switch (insn.getOpcode()) {
                case ISTORE, LSTORE, FSTORE, DSTORE, ASTORE, ANEWARRAY, ARETURN, ARRAYLENGTH, ATHROW, CHECKCAST, D2F, D2I, D2L, DNEG, DRETURN, F2D, F2I, F2L, FNEG, FRETURN, GETFIELD, TABLESWITCH, PUTSTATIC, POP2, L2I, L2F, LNEG, LRETURN, MONITORENTER, MONITOREXIT, POP, I2B, I2C, I2D, I2F, I2L, I2S, INEG, IRETURN, L2D -> 1;
                case AALOAD, BALOAD, CALOAD, DADD, DALOAD, DCMPG, DCMPL, DDIV, DMUL, DREM, DSUB, FADD, FALOAD, FCMPG, FCMPL, FDIV, FMUL, FREM, FSUB, SALOAD, PUTFIELD, LSHR, LSUB, LALOAD, LCMP, LDIV, LMUL, LOR, LREM, LSHL, LUSHR, LXOR, LADD, IADD, IALOAD, IAND, IDIV, IMUL, IOR, IREM, ISHL, ISHR, ISUB, IUSHR, IXOR -> 2;
                case AASTORE, BASTORE, CASTORE, DASTORE, FASTORE, SASTORE, LASTORE, IASTORE -> 3;
                default -> 0;
            };
        }
    }

    public static boolean isConstant(AbstractInsnNode insn) {
        if(insn instanceof LdcInsnNode){
            return true;
        }else if(insn instanceof IntInsnNode){
            return true;
        }

        int opcode = insn.getOpcode();
        return opcode == ICONST_M1 || opcode == ICONST_0 || opcode == ICONST_1 || opcode == ICONST_2 || opcode == ICONST_3 || opcode == ICONST_4 || opcode == ICONST_5 || opcode == LCONST_0 || opcode == LCONST_1 || opcode == FCONST_0 || opcode == FCONST_1 || opcode == FCONST_2 || opcode == DCONST_0 || opcode == DCONST_1;
    }

    public static int getCompare(Type type){
        if (type == Type.FLOAT_TYPE) {
            return FCMPL;
        }else if (type == Type.DOUBLE_TYPE) {
            return DCMPL;
        }else if (type == Type.LONG_TYPE) {
            return LCMP;
        }else {
            throw new IllegalArgumentException("Type " + type + " is not allowed!");
        }
    }

    public static Object getConstant(AbstractInsnNode insn) {
        if(!isConstant(insn)) {
            throw new IllegalArgumentException("Not a constant instruction!");
        }

        if(insn instanceof LdcInsnNode cst){
            return cst.cst;
        }else if(insn instanceof IntInsnNode cst){
            return cst.operand;
        }

        int opcode = insn.getOpcode();

        return switch (opcode) {
            case ICONST_M1 -> -1;
            case ICONST_0 -> 0;
            case ICONST_1 -> 1;
            case ICONST_2 -> 2;
            case ICONST_3 -> 3;
            case ICONST_4 -> 4;
            case ICONST_5 -> 5;
            case LCONST_0 -> 0L;
            case LCONST_1 -> 1L;
            case FCONST_0 -> 0.0f;
            case FCONST_1 -> 1.0f;
            case FCONST_2 -> 2.0f;
            case DCONST_0 -> 0.0;
            case DCONST_1 -> 1.0;
            default -> {throw new UnsupportedOperationException("Opcode " + opcode + " is not supported!");}
        };
    }

    public static MethodNode copy(MethodNode original){
        ClassNode classNode = new ClassNode();
        original.accept(classNode);
        return classNode.methods.get(0);
    }

    public static int getActualSize(InsnList insns){
        int size = 1;
        AbstractInsnNode node = insns.getFirst();
        while(node != null){
            size++;
            node = node.getNext();
        }
        return size;
    }

    public static void renameInstructions(ClassNode classNode, String previousName, String newName){
        for(MethodNode method : classNode.methods){
            for(AbstractInsnNode insn : method.instructions.toArray()){
                if(insn instanceof MethodInsnNode methodCall){
                    if(methodCall.owner.equals(previousName)){
                        methodCall.owner = newName;
                    }

                    Type[] args = Type.getArgumentTypes(methodCall.desc);
                    for(int i = 0; i < args.length; i++){
                        if(args[i].getClassName().replace('.', '/').equals(previousName)){
                            args[i] = Type.getObjectType(newName);
                        }
                    }
                    methodCall.desc = Type.getMethodDescriptor(Type.getReturnType(methodCall.desc), args);
                }else if(insn instanceof FieldInsnNode field){
                    if(field.owner.equals(previousName)){
                        field.owner = newName;
                    }
                }else if(insn instanceof InvokeDynamicInsnNode dynamicCall){
                    Type[] args = Type.getArgumentTypes(dynamicCall.desc);
                    for(int i = 0; i < args.length; i++){
                        if(args[i].getClassName().replace('.', '/').equals(previousName)){
                            args[i] = Type.getObjectType(newName);
                        }
                    }
                    dynamicCall.desc = Type.getMethodDescriptor(Type.getReturnType(dynamicCall.desc), args);

                    for (int i = 0; i < dynamicCall.bsmArgs.length; i++) {
                        Object arg = dynamicCall.bsmArgs[i];
                        if (arg instanceof Handle handle){
                            int tag = handle.getTag();
                            String owner = handle.getOwner();
                            String name = handle.getName();
                            String desc = handle.getDesc();
                            boolean itf = handle.isInterface();

                            if(owner.equals(previousName)){
                                owner = newName;
                            }

                            Type[] types = Type.getArgumentTypes(desc);
                            for(int j = 0; j < types.length; j++){
                                if(types[j].getClassName().replace('.', '/').equals(previousName)){
                                    types[j] = Type.getObjectType(newName);
                                }
                            }
                            desc = Type.getMethodDescriptor(Type.getReturnType(desc), types);

                            dynamicCall.bsmArgs[i] = new Handle(tag, owner, name, desc, itf);
                        }else if(arg instanceof Type type){
                            if(type.getSort() == Type.METHOD){
                                Type[] types = Type.getArgumentTypes(type.getDescriptor());
                                for(int j = 0; j < types.length; j++){
                                    if(types[j].getClassName().replace('.', '/').equals(previousName)){
                                        types[j] = Type.getObjectType(newName);
                                    }
                                }
                                dynamicCall.bsmArgs[i] = Type.getMethodType(Type.getReturnType(type.getDescriptor()), types);
                            }else if(type.getClassName().replace('.', '/').equals(previousName)){
                                dynamicCall.bsmArgs[i] = Type.getObjectType(newName);
                            }
                        }
                    }
                }
            }
        }
    }

    public static void rename(ClassNode classNode, String s) {
        String previousName = classNode.name;
        classNode.name = s;
        renameInstructions(classNode, previousName, s);
    }

    public static void changeFieldType(ClassNode target, FieldID fieldID, Type newType) {
        String owner = target.name;
        String name = fieldID.name();
        String desc = fieldID.desc().getDescriptor();

        FieldNode field = target.fields.stream().filter(f -> f.name.equals(name) && f.desc.equals(desc)).findFirst().orElse(null);
        if (field == null) {
            throw new IllegalArgumentException("Field " + name + " not found!");
        }

        field.desc = newType.getDescriptor();

        for (MethodNode method : target.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof FieldInsnNode fieldInsn) {
                    if (fieldInsn.owner.equals(owner) && fieldInsn.name.equals(name) && fieldInsn.desc.equals(desc)) {
                        fieldInsn.desc = newType.getDescriptor();
                    }
                }
            }
        }

    }

    public static int getDimensions(Type t){
        if(t.getSort() == Type.ARRAY){
            return t.getDimensions();
        }else{
            return 0;
        }
    }
}
