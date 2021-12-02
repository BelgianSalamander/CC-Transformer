package me.salamander.cctransformer;

import me.salamander.cctransformer.transformer.TypeTransformer;
import me.salamander.cctransformer.transformer.analysis.AnalysisResults;
import me.salamander.cctransformer.transformer.analysis.TransformTrackingValue;
import me.salamander.cctransformer.transformer.config.Config;
import me.salamander.cctransformer.transformer.config.ConfigLoader;
import me.salamander.cctransformer.transformer.config.TransformType;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.DynamicGraphMinFixedPoint;
import net.minecraft.world.level.lighting.LayerLightEngine;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public class CCTransformer {
    public static void main(String[] args) {
        Config config = getConfig();

        ClassNode testClass = loadClass(DynamicGraphMinFixedPoint.class);
        TypeTransformer typeTransformer = new TypeTransformer(config, testClass);

        typeTransformer.analyzeAllMethods();
    }

    private static ClassNode loadClass(Class<?> clazz) {
        ClassNode classNode = new ClassNode();
        try {
            InputStream is = ClassLoader.getSystemResourceAsStream(clazz.getName().replace('.', '/') + ".class");
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
