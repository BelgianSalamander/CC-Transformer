package me.salamander.cctransformer;

import me.salamander.cctransformer.transformer.TypeTransformer;
import me.salamander.cctransformer.transformer.config.Config;
import me.salamander.cctransformer.transformer.config.ConfigLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;

public class CCTransformer {
    public static void main(String[] args) {
        Config config = getConfig();

        //ClassNode testClass = loadClass(BlockLightEngine.class);
        ClassNode testClass = loadClass("net.minecraft.world.level.lighting.DynamicGraphMinFixedPoint");
        //ClassNode testClass = loadClass("net.minecraft.world.level.lighting.BlockLightEngine");

        TypeTransformer typeTransformer = new TypeTransformer(config, testClass, false, false);

        typeTransformer.analyzeAllMethods();

        typeTransformer.makeConstructor("(III)V", dynGraphConstructor());

        typeTransformer.transformAllMethods();

        typeTransformer.saveTransformedClass();
        Class<?> clazz = typeTransformer.loadTransformedClass();
        try {
            Constructor<?>[] constructors = clazz.getConstructors();
            Constructor<?> constructor = constructors[0];
            constructor.newInstance(new Object[]{null});
        }catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static InsnList dynGraphConstructor(){
        LabelNode l1 = new LabelNode();
        LabelNode l2 = new LabelNode();
        LabelNode l3 = new LabelNode();

        InsnList l = new InsnList();
        l.add(new VarInsnNode(Opcodes.ALOAD, 0));
        l.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        l.add(new VarInsnNode(Opcodes.ILOAD, 1));
        l.add(new IntInsnNode(Opcodes.SIPUSH, 254));
        l.add(new JumpInsnNode(Opcodes.IF_ICMPLT, l1));
        l.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalArgumentException"));
        l.add(new InsnNode(Opcodes.DUP));
        l.add(new LdcInsnNode("Level count must be < 254."));
        l.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "(Ljava/lang/String;)V", false));
        l.add(new InsnNode(Opcodes.ATHROW));
        l.add(l1);
        l.add(new VarInsnNode(Opcodes.ALOAD, 0));
        l.add(new VarInsnNode(Opcodes.ILOAD, 1));
        l.add(new FieldInsnNode(Opcodes.PUTFIELD, "net/minecraft/world/level/lighting/DynamicGraphMinFixedPoint", "levelCount", "I"));
        l.add(new VarInsnNode(Opcodes.ALOAD, 0));
        l.add(new VarInsnNode(Opcodes.ILOAD, 1));
        l.add(new TypeInsnNode(Opcodes.ANEWARRAY, "me/salamander/cctransformer/util/LinkedInt3HashSet"));
        //l.add(new FieldInsnNode(Opcodes.PUTFIELD, "net/minecraft/world/level/lighting/DynamicGraphMinFixedPoint", "queues", "[Lme/salamander/cctransformer/util/LinkedInt3HashSet;"));
        l.add(new FieldInsnNode(Opcodes.PUTFIELD, "net/minecraft/world/level/lighting/DynamicGraphMinFixedPoint", "queues", "Ljava/lang/Object;"));
        l.add(new InsnNode(Opcodes.ICONST_0));
        l.add(new VarInsnNode(Opcodes.ISTORE, 4));
        l.add(l3);
        l.add(new VarInsnNode(Opcodes.ILOAD, 4));
        l.add(new VarInsnNode(Opcodes.ILOAD, 1));
        l.add(new JumpInsnNode(Opcodes.IF_ICMPGE, l2));
        l.add(new VarInsnNode(Opcodes.ALOAD, 0));
        //l.add(new FieldInsnNode(Opcodes.GETFIELD, "net/minecraft/world/level/lighting/DynamicGraphMinFixedPoint", "queues", "[Lme/salamander/cctransformer/util/LinkedInt3HashSet;"));
        l.add(new FieldInsnNode(Opcodes.GETFIELD, "net/minecraft/world/level/lighting/DynamicGraphMinFixedPoint", "queues", "Ljava/lang/Object;"));
        l.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Lme/salamander/cctransformer/util/LinkedInt3HashSet;"));
        l.add(new VarInsnNode(Opcodes.ILOAD, 4));
        l.add(new TypeInsnNode(Opcodes.NEW, "me/salamander/cctransformer/util/LinkedInt3HashSet"));
        l.add(new InsnNode(Opcodes.DUP));
        l.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "me/salamander/cctransformer/util/LinkedInt3HashSet", "<init>", "()V", false));
        l.add(new InsnNode(Opcodes.AASTORE));
        l.add(new IincInsnNode(4, 1));
        l.add(new JumpInsnNode(Opcodes.GOTO, l3));
        l.add(l2);
        l.add(new VarInsnNode(Opcodes.ALOAD, 0));
        l.add(new TypeInsnNode(Opcodes.NEW, "me/salamander/cctransformer/util/Int3UByteLinkedHashMap"));
        l.add(new InsnNode(Opcodes.DUP));
        l.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "me/salamander/cctransformer/util/Int3UByteLinkedHashMap", "<init>", "()V", false));
        //l.add(new FieldInsnNode(Opcodes.PUTFIELD, "net/minecraft/world/level/lighting/DynamicGraphMinFixedPoint", "computedLevels", "Lme/salamander/cctransformer/util/Int3UByteLinkedHashMap;"));
        l.add(new FieldInsnNode(Opcodes.PUTFIELD, "net/minecraft/world/level/lighting/DynamicGraphMinFixedPoint", "computedLevels", "Ljava/lang/Object;"));
        l.add(new VarInsnNode(Opcodes.ALOAD, 0));
        l.add(new VarInsnNode(Opcodes.ILOAD, 1));
        l.add(new FieldInsnNode(Opcodes.PUTFIELD, "net/minecraft/world/level/lighting/DynamicGraphMinFixedPoint", "firstQueuedLevel", "I"));
        l.add(new InsnNode(Opcodes.RETURN));
        return l;
    }


    private static ClassNode loadClass(Class<?> clazz) {
        return loadClass(clazz.getName());
    }

    private static ClassNode loadClass(String name) {
        ClassNode classNode = new ClassNode();
        try {
            InputStream is = ClassLoader.getSystemResourceAsStream(name.replace('.', '/') + ".class");
            ClassReader classReader = new ClassReader(is);
            classReader.accept(classNode, 0);
        }catch (IOException e) {
            e.printStackTrace();
            return null;
        }

        return classNode;
    }

    private static Config getConfig(){
        InputStream is = CCTransformer.class.getResourceAsStream("/type-transform.json");
        Config config = ConfigLoader.loadConfig(is);
        try {
            is.close();
        }catch (IOException e) {
            e.printStackTrace();
        }
        return config;
    }
}
